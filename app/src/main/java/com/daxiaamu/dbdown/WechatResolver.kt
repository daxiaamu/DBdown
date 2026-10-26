package com.daxiaamu.dbdown

import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** Resolves public previews first; authenticated exchange stays on Tencent's own servers. */
internal class WechatResolver(private val trackCall: (okhttp3.Call) -> Unit = {}) {
    fun resolve(link: VideoLink, selection: TrackSelection?): VideoInfo {
        val preview = post("https://channels.weixin.qq.com/finder-preview/api/feed/get_feed_info",
            JSONObject().put("baseReq", JSONObject().put("generalToken", "")).put("shortUri", link.key.removePrefix("wx:")),
            "https://channels.weixin.qq.com/finder-preview/pages/sph?id=${link.key.removePrefix("wx:")}")
        if(preview.optJSONObject("data")?.optJSONObject("feedInfo")?.let(::hasVideo) == true)
            return wechatVideoInfo(link, preview, selection)
        val parsed = post("https://yuanbao.tencent.com/api/weixin/get_parse_result",
            JSONObject().put("type", "video_channel_url").put("url", link.url).put("scene", 1), "https://yuanbao.tencent.com/")
        check(parsed.optInt("code", -1) == 0) { "视频号分享链接暂时无法解析，请重新登录腾讯元宝后重试" }
        val playable = parsed.optJSONObject("data")?.optString("playable_url")?.toHttpUrlOrNull()
            ?: error("平台未返回视频号播放信息，此作品可能已删除或无法公开访问")
        check(playable.isHttps && playable.host == "channels.weixin.qq.com") { "视频号播放信息无效" }
        val token = playable.queryParameter("token")?.takeIf(String::isNotBlank) ?: error("视频号播放凭证为空，请重新登录腾讯元宝")
        val eid = playable.queryParameter("eid")?.takeIf(String::isNotBlank) ?: error("视频号作品标识为空")
        val feed = post("https://channels.weixin.qq.com/finder-preview/api/feed/get_feed_info",
            JSONObject().put("baseReq", JSONObject().put("generalToken", token)).put("exportId", eid), playable.toString())
        return wechatVideoInfo(link, feed, selection)
    }

    private fun post(url: String, body: JSONObject, referer: String): JSONObject {
        val request = Request.Builder().url(url).header("User-Agent", VideoResolver.DESKTOP)
            .header("Referer", referer).header("Origin", referer.toHttpUrlOrNull()!!.let { "https://${it.host}" })
            .header("x-source", "web")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return VideoResolver.client.newCall(request).also(trackCall).execute().use { response ->
            if(response.code == 401) {
                WebAccounts.refresh(true)
                error("请先在设置中登录微信视频号使用的腾讯元宝账号，再重试下载")
            }
            check(response.isSuccessful) { "视频号服务器返回 ${response.code}，请稍后重试" }
            JSONObject(response.body?.string() ?: error("视频号服务器未返回内容"))
        }
    }
    private fun hasVideo(feed: JSONObject) = listOf(feed.optString("originVideoUrl"), feed.optString("videoUrl"),
        feed.optJSONObject("h265VideoInfo")?.optString("videoUrl"), feed.optJSONObject("h264VideoInfo")?.optString("videoUrl"))
        .any { !it.isNullOrBlank() }
}

internal fun wechatVideoInfo(link: VideoLink, response: JSONObject, selection: TrackSelection?): VideoInfo {
    check(response.optInt("errCode", -1) == 0) { "视频号播放凭证已失效，请重新解析作品" }
    val feed = response.optJSONObject("data")?.optJSONObject("feedInfo") ?: error("视频号未返回作品信息")
    val candidates = listOf(
        Triple("wx:original", "原始画质", feed.optString("originVideoUrl")),
        Triple("wx:hevc", "HEVC 画质", feed.optJSONObject("h265VideoInfo")?.optString("videoUrl").orEmpty()),
        Triple("wx:avc", "AVC 画质", feed.optJSONObject("h264VideoInfo")?.optString("videoUrl").orEmpty()),
        Triple("wx:default", "默认画质", feed.optString("videoUrl"))
    ).filter { (_, _, url) -> url.toHttpUrlOrNull()?.isHttps == true }.distinctBy { it.third }
    check(candidates.isNotEmpty()) { "此视频号作品未提供可下载的视频资源" }
    val chosen = if(selection == null) candidates.first() else candidates.firstOrNull { it.first == selection.video && selection.audio == null }
        ?: error("所选视频号规格已不可用，请重新选择")
    val key = feed.optString("decodeKey").takeUnless { it.isBlank() || it == "0" || it == "null" }
    check(key == null || key.toULongOrNull() != null) { "视频号动态密钥无效，请重新解析" }
    return VideoInfo(link, link.key, feed.optString("description").ifBlank { "微信视频号视频" }, chosen.third,
        quality = chosen.second, referer = "https://channels.weixin.qq.com/", userAgent = VideoResolver.DESKTOP,
        videoDecodeKey = key,
        specifications = MediaSpecifications(candidates.map { TrackOption(it.first, it.second, "视频内置音轨", true) },
            emptyList(), TrackSelection(chosen.first)))
}
