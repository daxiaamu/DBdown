package com.daxiaamu.dbdown

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class VideoInfo(
    val source: VideoLink, val id: String, val title: String,
    val video: String, val audio: String? = null, val quality: String = "",
    val referer: String, val userAgent: String, val images: List<String> = emptyList(),
    val music: String? = null, val videoFallbacks: List<String> = emptyList(), val resolution: String = "", val audioCodec: String = "", val imageVideos: List<String?> = emptyList(), val separateAlbumMusic: Boolean = false, val musicCandidates: List<String> = emptyList(),
    val videoPlan: SegmentPlan? = null, val audioPlan: SegmentPlan? = null, val audioUserAgent: String? = null, val audioFallbacks: List<String> = emptyList(), val fps: Float = 0f, val specifications: MediaSpecifications? = null, val directVideoTracks: List<DirectVideoTrack> = emptyList()
)

class VideoResolver(private val trackCall: (okhttp3.Call) -> Unit = {}) {
    companion object {
        const val DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0.0.0 Safari/537.36"
        const val MOBILE = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 Version/17.4 Mobile/15E148 Safari/604.1"
        val client = OkHttpClient.Builder().cookieJar(PlatformCookieJar()).connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
    }
    suspend fun resolve(link: VideoLink, selection: TrackSelection? = null): VideoInfo = withContext(Dispatchers.IO) {
        WebAccounts.refresh(true)
        val actual = expand(link)
        when(actual.platform) {
            Platform.YOUTUBE -> YoutubeResolver.resolve(actual, { coroutineContext.ensureActive() }, trackCall = trackCall, requested = selection)
            Platform.WEIBO -> WeiboResolver(trackCall) { coroutineContext.ensureActive() }.resolve(actual,selection)
            Platform.BILI -> bili(actual, selection)
            Platform.DOUYIN -> douyin(actual, selection)
        }
    }
    private fun get(url: String, ua: String = DESKTOP, referer: String = "https://www.bilibili.com/"): String {
        val request = Request.Builder().url(url).header("User-Agent", ua).header("Referer", referer).build()
        return client.newCall(request).also(trackCall).execute().use {
            check(it.isSuccessful) { "服务器返回 ${it.code}，请稍后重试" }
            it.body?.string() ?: error("服务器没有返回内容")
        }
    }
    private fun expand(link: VideoLink): VideoLink {
        if (!link.key.startsWith("douyin-short:") && !link.key.startsWith("b23.tv") && !link.key.startsWith("bili2233.cn")) return link
        val noRedirect = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        var url = link.url
        repeat(6) {
            noRedirect.newCall(Request.Builder().url(url).header("User-Agent", MOBILE).build()).also(trackCall).execute().use { response ->
                val next = if(response.code in 300..399) response.header("Location") else null
                if (next == null) {
                    return Links.fromUrl(url)?.takeUnless { it.key == link.key }
                        ?: error("分享链接没有指向视频，请复制视频的分享链接")
                }
                val target = response.request.url.resolve(next) ?: error("分享链接跳转无效")
                val host = target.host
                check(target.isHttps && (host == "b23.tv" || host == "bili2233.cn" ||
                    host == "bilibili.com" || host.endsWith(".bilibili.com") ||
                    host == "douyin.com" || host.endsWith(".douyin.com") ||
                    host == "iesdouyin.com" || host.endsWith(".iesdouyin.com"))) { "分享链接跳转到了不支持的网站" }
                url = target.toString()
                Links.fromUrl(url)?.takeUnless {
                    it.key.startsWith("douyin-short:") || it.key.startsWith("b23.tv") || it.key.startsWith("bili2233.cn")
                }?.let { return it }
            }
        }
        error("分享链接跳转次数过多")
    }
    private fun bili(link: VideoLink, selection: TrackSelection?): VideoInfo {
        val rawId = link.url.substringAfter("/video/").substringBefore("?").trimEnd('/')
        val query = if(rawId.startsWith("av")) "aid=${rawId.drop(2)}" else "bvid=$rawId"
        val data = api(get("https://api.bilibili.com/x/web-interface/view?$query"))
        val bvid = data.getString("bvid")
        val pages = data.getJSONArray("pages")
        check(link.part <= pages.length()) { "视频没有第 ${link.part} 个分 P" }
        val page = pages.getJSONObject(link.part - 1)
        val cid = page.getLong("cid")
        val play = api(get("https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=$cid&qn=127&fnval=4048&fourk=1"))
        val canonical = VideoLink(Platform.BILI, "https://www.bilibili.com/video/$bvid?p=${link.part}", "$bvid:p${link.part}", link.part)
        val title = data.getString("title") + if(pages.length() > 1) " · P${link.part} ${page.optString("part")}" else ""
        val dash = play.optJSONObject("dash")
        if (dash != null) {
            return biliVideoInfo(canonical,title,dash,selection)

        }
        val segments = play.optJSONArray("durl") ?: error("此视频暂无可下载资源，可能需要登录或会员权限")
        check(segments.length() == 1) { "暂不支持此视频的多段 FLV 格式" }
        val segment = segments.getJSONObject(0)
        check(play.optString("format").contains("mp4")) { "暂不支持此视频格式" }
        check(selection == null || (selection.video == "bili:combined" && selection.audio == null)) { "所选规格已不可用，请重新选择" }
        return VideoInfo(canonical, canonical.key, title, https(segment.getString("url")), quality = "默认画质", referer = canonical.url, userAgent = DESKTOP, videoFallbacks = biliBackupUrls(segment),
            specifications = MediaSpecifications(listOf(TrackOption("bili:combined","原始画质", "自带音轨",true)),emptyList(),TrackSelection("bili:combined")))
    }
    private fun api(text: String): JSONObject {
        val obj = JSONObject(text)
        if(obj.optInt("code") == -101) WebAccounts.refresh(true)
        check(obj.optInt("code", -1) == 0) {
            when(obj.optInt("code")) {
                -404 -> "视频不存在或已删除"
                -403, -101, -104 -> "此视频需要登录或没有访问权限"
                -352, -412 -> "平台暂时限制访问，请稍后重试"
                else -> obj.optString("message", "解析失败，请稍后重试")
            }
        }
        return obj.getJSONObject("data")
    }
    private fun streamUrl(value: JSONObject) = https(value.optString("baseUrl").ifEmpty { value.getString("base_url") })
    private suspend fun douyin(link: VideoLink, selection: TrackSelection?): VideoInfo {
        val id = link.key.removePrefix("dy:")
        if(link.url.contains("/slides/")) {
            val raw = get("https://www.iesdouyin.com/web/api/v2/aweme/slidesinfo/?aweme_ids=%5B$id%5D&request_source=200", MOBILE, "https://www.iesdouyin.com/share/slides/$id/")
            val info = if(DouyinPage.needsDesktopLive(raw, link)) {
                DouyinPage.supplementDesktop(raw, DouyinDesktop.detail(id), link)
            } else DouyinPage.parseSlides(raw, link)
            return resolveAlbumMusic(info)
        }
        val kind = if(link.url.contains("/note/")) "note" else "video"
        var page = try {
            get("https://www.douyin.com/share/$kind/$id/", MOBILE, "https://www.douyin.com/")
        } catch(first: java.io.IOException) {
            get("https://www.iesdouyin.com/share/$kind/$id/", MOBILE, "https://www.douyin.com/")
        }
        if(!page.contains("videoInfoRes")) {
            page = get("https://www.douyin.com/share/$kind/$id/", MOBILE, "https://www.douyin.com/")
        }
        val info = DouyinPage.parse(page, link)
        if(info.images.isNotEmpty()) return resolveAlbumMusic(
            DouyinPage.supplementNote(page, DouyinDesktop.detail(id), link))
        // A usable share URL can still point to a lower-quality encode. Compare before downloading.
        val enriched = try {
            withTimeoutOrNull(12_000) {
                DouyinPage.supplementVideo(page, DouyinDesktop.detail(id), info)
            } ?: info
        } catch(cancelled: CancellationException) {
            throw cancelled
        } catch(_: Exception) {
            info
        }
        return DouyinPage.selectSpecification(enriched, selection)
    }
    private fun resolveAlbumMusic(info: VideoInfo): VideoInfo {
        if(info.images.isEmpty() || info.music != null) return info
        for(url in info.musicCandidates) {
            val audio = try {
                client.newCall(Request.Builder().url(url).header("User-Agent", info.userAgent)
                    .header("Referer", info.referer).header("Range", "bytes=0-4095").build())
                    .also(trackCall).execute().use { response ->
                        if(!response.isSuccessful) false else response.body?.let { body ->
                            val header = ByteArray(32)
                            var count = 0
                            body.byteStream().use { input ->
                                while(count < header.size) {
                                    val read = input.read(header, count, header.size - count)
                                    if(read < 0) break
                                    count += read
                                }
                            }
                            isAlbumAudio(header.copyOf(count), response.header("Content-Type").orEmpty())
                        } ?: false
                    }
            } catch(_: java.io.IOException) { false }
            if(audio) return info.copy(music = url)
        }
        return info
    }
    private fun https(url: String) = if(url.startsWith("http://")) "https://" + url.removePrefix("http://") else url
}
