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
                    Response(it.code, it.message, it.headers.toMultimap(), it.body?.string(), it.request.url.toString())
                }
            }
        })
    }
    fun resolve(link: VideoLink, trackCall: (Call) -> Unit): VideoInfo {
        tracking.set(trackCall)
        try {
            if(WebAccounts.youtubeSessionPresent()) {
                val snapshot = WebAccounts.youtubeCookieSnapshot()
                val page = client.newCall(Request.Builder().url(link.url).header("User-Agent", VideoResolver.DESKTOP).build())
                    .also(trackCall).execute().use {
                        check(it.isSuccessful) { "YouTube 网页返回 ${it.code}，请稍后重试" }
                        it.body?.string().orEmpty()
                    }
                WebAccounts.observeYoutubeSession(page, snapshot)
                val webInfo = runCatching { youtubeWebVideo(page, link) }.getOrNull()
                if(webInfo != null && listOfNotNull(webInfo.video, webInfo.audio).all { url ->
                    client.newCall(Request.Builder().url(url).header("User-Agent", VideoResolver.DESKTOP)
                        .header("Referer", link.url).header("Range", "bytes=0-0").build()).also(trackCall)
                        .execute().use { it.code == 200 || it.code == 206 }
                }) return webInfo
                // A web stream may require an additional platform challenge. Try the public extractor.

            }
            val extractor = ServiceList.YouTube.getStreamExtractor(link.url)
            extractor.fetchPage()
            check(extractor.streamType == StreamType.VIDEO_STREAM) { "暂不支持 YouTube 直播，请在直播结束后下载普通视频" }
            val audio = bestYoutubeAudio(extractor.audioStreams)
            val video = bestYoutubeVideo(extractor.videoStreams + extractor.videoOnlyStreams, audio != null)
                ?: error("这个 YouTube 视频没有可下载的音视频资源")
            return VideoInfo(link, link.key, extractor.name, video.content,
                audio = audio?.content, audioCodec = if(audio?.format == MediaFormat.WEBMA) "opus" else "aac", quality = video.getResolution(),
                referer = link.url, userAgent = VideoResolver.DESKTOP,
                resolution = resolutionLabel(video.width, video.height))
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
        } finally { tracking.remove() }
    }
}

internal fun bestYoutubeVideo(streams: List<VideoStream>, hasAudio: Boolean): VideoStream? = streams
    .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.format in setOf(MediaFormat.MPEG_4, MediaFormat.WEBM) &&
        (!it.isVideoOnly() || hasAudio) && listOf("avc", "hev", "hvc", "av01", "vp9", "vp09").any(it.codec.orEmpty()::startsWith) }
    .maxWithOrNull(compareBy<VideoStream> { it.width.toLong() * it.height }
        .thenBy { Regex("^[0-9]+").find(it.getResolution())?.value?.toIntOrNull() ?: 0 }.thenBy { it.fps }.thenBy { it.bitrate })

/** Keep the original language before comparing bitrate; never pick a dub just for more bits. */
internal fun bestYoutubeAudio(streams: List<AudioStream>): AudioStream? = streams.filter {
    it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && (it.format == MediaFormat.M4A || (it.format == MediaFormat.WEBMA && it.codec.orEmpty().startsWith("opus")))
}.maxWithOrNull(compareBy<AudioStream> { it.audioTrackType == AudioTrackType.ORIGINAL }
    .thenBy { if(it.averageBitrate > 0) it.averageBitrate.toLong() * 1000 else it.bitrate.toLong() }
    .thenBy { it.bitrate })
