package com.daxiaamu.dbdown

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.*
import java.util.concurrent.TimeUnit

/** One downloader for the library, with task-local cancellation tracking on the blocking IO thread. */
internal object YoutubeResolver {
    private val players = ThreadLocal<MutableList<org.json.JSONObject>>()
    private val tracking = ThreadLocal<(Call) -> Unit>()
    private val client = OkHttpClient.Builder().cookieJar(object : okhttp3.CookieJar {
        override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> =
            if(url.isHttps && (url.host == "youtube.com" || url.host.endsWith(".youtube.com"))) WebAccounts.cookies(url) else emptyList()
        override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
            if(url.isHttps && (url.host == "youtube.com" || url.host.endsWith(".youtube.com"))) WebAccounts.save(url, cookies)
        }
    }).connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
    init {
        NewPipe.init(object : Downloader() {
            override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): Response {
                val method = request.httpMethod()
                val body = request.dataToSend()?.toRequestBody()
                    ?: if(method in listOf("POST", "PUT", "PATCH")) ByteArray(0).toRequestBody() else null
                val builder = Request.Builder().url(request.url()).method(method, body)
                    .header("User-Agent", VideoResolver.DESKTOP)
                request.headers().forEach { (key, values) ->
                    builder.removeHeader(key)
                    values.forEach { builder.addHeader(key, it) }
                }
                return client.newCall(builder.build()).also { tracking.get()?.invoke(it) }.execute().use {
                    val body = it.body?.string()
                    // The extractor's static itag list omits HDR and some high-resolution formats.
                    // Keep only task-local first-party player responses and reuse its signature handling.
                    if((it.request.url.host == "youtubei.googleapis.com" || it.request.url.host == "youtube.com" ||
                        it.request.url.host.endsWith(".youtube.com")) && it.request.url.encodedPath.endsWith("/player")) {
                        players.get()?.takeIf { list -> list.size < 8 }?.let { list ->
                            runCatching { org.json.JSONObject(body.orEmpty()).put("_dbdownUa",it.request.header("User-Agent") ?: VideoResolver.DESKTOP)
                                .put("_dbdownSource","NewPipe") }.getOrNull()?.let(list::add)
                        }
                    }
                    Response(it.code, it.message, it.headers.toMultimap(), body, it.request.url.toString())
                }
            }
        })
    }
    fun resolve(link: VideoLink, ensureActive: () -> Unit = {}, onCandidates: (List<YoutubeStream>) -> Unit = {}, onClient: (String,String) -> Unit = { _,_ -> }, trackCall: (Call) -> Unit): VideoInfo {
        val register: (Call)->Unit = { ensureActive(); trackCall(it) }
        fun active() {
            ensureActive()
            if(Thread.currentThread().isInterrupted)
                throw kotlinx.coroutines.CancellationException("YouTube resolution cancelled")
        }
        fun <T> attempt(block: () -> T): T? = try { active(); block() } catch(e: Exception) {
            active(); if(e is kotlinx.coroutines.CancellationException) throw e; null
        }
        tracking.set(register)
        players.set(mutableListOf())
        try {
            val snapshot = WebAccounts.youtubeCookieSnapshot()
            attempt {
                val page = client.newCall(Request.Builder().url(link.url).header("User-Agent",VideoResolver.DESKTOP).build())
                    .also(register).execute().use { check(it.isSuccessful); it.body!!.string() }
                if(WebAccounts.youtubeSessionPresent()) WebAccounts.observeYoutubeSession(page,snapshot)
                youtubePlayerResponse(page)?.put("_dbdownSource","WEB_PAGE")?.let { players.get()!!.add(it) }
            }
            val extractor = ServiceList.YouTube.getStreamExtractor(link.url)
            var extractionError: Exception? = null
            val fetched = try { extractor.fetchPage(); true } catch(e: Exception) { active(); extractionError=e; false }
            if(fetched) check(extractor.streamType == StreamType.VIDEO_STREAM) { "暂不支持 YouTube 直播" }
            YoutubeClients.profiles().forEach { profile ->
                val response=attempt { YoutubeClients.fetch(profile,link,client,register) }
                onClient(profile.name,response?.optJSONObject("playabilityStatus")?.optString("status") ?: "unavailable")
                response?.let { players.get()!!.add(it) }
            }
            val responses = players.get().orEmpty().filter { youtubeMatchingPlayer(it,link) }
            val media = client.newBuilder().cookieJar(okhttp3.CookieJar.NO_COOKIES)
                .callTimeout(8,TimeUnit.SECONDS).addNetworkInterceptor { chain ->
                    check(youtubeMediaUrl(chain.request().url.toString())) { "Unexpected media host" }
                    chain.proceed(chain.request())
                }.build()
            fun read(url: String, ua: String): ByteArray {
                check(youtubeMediaUrl(url)); active()
                return media.newCall(Request.Builder().url(url).header("User-Agent",ua).header("Referer",link.url).build())
                    .also(register).execute().use {
                        check(it.isSuccessful)
                        val bytes=it.body!!.byteStream().readNBytes(4*1024*1024+1)
                        check(bytes.size<=4*1024*1024); bytes
                    }
            }
            val manifests=YoutubeManifests(::read)
            val candidates=mutableListOf<YoutubeStream>()
            responses.forEach { player ->
                candidates += youtubeDirectStreams(player,link)
                val data=player.optJSONObject("streamingData") ?: return@forEach
                val ua=player.optString("_dbdownUa",VideoResolver.DESKTOP)
                val source=player.optString("_dbdownSource","player")
                for((field,dash) in listOf("dashManifestUrl" to true,"hlsManifestUrl" to false)) {
                    val rawUrl=data.optString(field)
                    if(rawUrl.isNotBlank()) attempt {
                        val url=decodeYoutubeManifestUrl(link.key.removePrefix("yt:"),rawUrl)
                        if(dash) manifests.dash(url,ua,source) else manifests.hls(url,ua,source)
                    }?.let(candidates::addAll)
                }
            }
            // Keep supported extractor resources too; they may include client-specific URL fixes.
            if(fetched) {
                attempt { extractor.audioStreams }?.forEach { audio ->
                    if(audio.isUrl && audio.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                        (audio.format == MediaFormat.M4A || audio.codec.orEmpty().startsWith("opus"))) {
                        candidates += YoutubeStream(audio.content,false,true,audio.codec.orEmpty(),bitrate=
                            if(audio.averageBitrate>0) audio.averageBitrate*1000L else audio.bitrate.toLong(),
                            language=audio.audioTrackId ?: audio.audioLocale?.toLanguageTag() ?: audio.audioTrackType?.name.orEmpty(),
                            original=audio.audioTrackType==AudioTrackType.ORIGINAL,channels=audio.itagItem?.audioChannels ?: 0,
                            formatId=audio.itag.toString(),source="NewPipe formatted")
                    }
                }
                attempt { extractor.videoStreams + extractor.videoOnlyStreams }?.forEach { video ->
                    if(video.isUrl && video.deliveryMethod==DeliveryMethod.PROGRESSIVE_HTTP) {
                        candidates += YoutubeStream(video.content,true,!video.isVideoOnly(),video.codec.orEmpty(),video.width,video.height,
                            video.fps.toFloat(),bitrate=video.bitrate.toLong(),formatId=video.itag.toString(),source="NewPipe formatted")
                    }
                }
            }
            onCandidates(candidates)
            val selection=youtubeSelectStreams(candidates) { stream -> attempt {
                val ready=manifests.prepare(stream)
                val probes=ready.plan?.segments?.let { listOf(it.first(),it.last()).distinct() }
                    ?: listOf(MediaSegment(ready.url))
                for(part in probes) {
                    check(youtubeMediaUrl(part.url)); active()
                    media.newCall(Request.Builder().url(part.url).header("User-Agent",ready.userAgent)
                        .header("Referer",link.url).header("Range","bytes=${part.start}-${part.start}").build())
                        .also(register).execute().use { check(it.code==200 || it.code==206) }
                }
                ready
            } }
            if(selection==null) {
                if(extractionError!=null) throw extractionError
                error("暂未获取到可下载的 YouTube 资源，请重试或在设置中登录")
            }
            val (video,audio)=selection
            val title=responses.firstOrNull()?.optJSONObject("videoDetails")?.optString("title")
                ?: if(fetched) extractor.name else "YouTube " + link.key.removePrefix("yt:")
            return VideoInfo(link,link.key,title,video.url,audio=audio?.url,audioCodec=audio?.audioCodec.orEmpty(),
                quality="${video.height}p${if(video.fps>30) video.fps.toInt() else ""}${if(video.hdr) " HDR" else ""}",
                referer=link.url,userAgent=video.userAgent,resolution=resolutionLabel(video.width,video.height),
                videoPlan=video.plan,audioPlan=audio?.plan,audioUserAgent=audio?.userAgent)
        } catch(e: org.schabi.newpipe.extractor.exceptions.ExtractionException) {
            val message = when(e) {
                is org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException,
                is org.schabi.newpipe.extractor.exceptions.ReCaptchaException -> "YouTube 要求验证访问，请稍后重试或更换可访问 YouTube 的网络"
                is org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException -> "这个 YouTube 视频需要年龄验证，请在设置中登录有权限的账号后重试"
                is org.schabi.newpipe.extractor.exceptions.PrivateContentException -> "当前账号无法获取这个私密视频，请确认登录账号及访问权限"
                is org.schabi.newpipe.extractor.exceptions.PaidContentException -> "当前账号无法获取这个视频，请确认会员或付费权限"
                is org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException -> "这个 YouTube 视频在当前地区不可用"
                is org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException -> "这个 YouTube 视频当前不可用，可能已删除或受访问限制"
                else -> "YouTube 解析失败，请确认网络可访问 YouTube 后重试"
            }
            throw IllegalStateException(message, e)
        } finally { tracking.remove(); players.remove() }
    }
}

internal fun bestYoutubeVideo(streams: List<VideoStream>, hasAudio: Boolean): VideoStream? = streams
    .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.format in setOf(MediaFormat.MPEG_4, MediaFormat.WEBM) &&
        (!it.isVideoOnly() || hasAudio) && listOf("avc", "hev", "hvc", "av01", "vp9", "vp09").any(it.codec.orEmpty()::startsWith) }
    .maxWithOrNull(compareBy<VideoStream> { it.width.toLong() * it.height }
        .thenBy { Regex("^[0-9]+").find(it.getResolution())?.value?.toIntOrNull() ?: 0 }.thenBy { it.fps }.thenBy { it.bitrate })

/** Keep the original language before comparing bitrate; never pick a dub just for more bits. */
internal fun bestYoutubeAudio(streams: List<AudioStream>): AudioStream? = streams.filter {
    it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && (it.format == MediaFormat.M4A || (it.format in setOf(MediaFormat.WEBMA, MediaFormat.WEBMA_OPUS) && it.codec.orEmpty().startsWith("opus")))
}.maxWithOrNull(compareBy<AudioStream> { it.audioTrackType == AudioTrackType.ORIGINAL }
    .thenBy { if(it.averageBitrate > 0) it.averageBitrate.toLong() * 1000 else it.bitrate.toLong() }
    .thenBy { it.bitrate })
