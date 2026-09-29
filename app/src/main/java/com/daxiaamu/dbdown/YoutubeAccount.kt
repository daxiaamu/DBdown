package com.daxiaamu.dbdown

import org.json.JSONObject
import org.json.JSONTokener
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import java.net.URLDecoder

/** Parse only explicit first-party config, never login words in page text or video titles. */
internal fun youtubeAccountVerdict(page: String): AccountStatus {
    val flags = Regex("""ytcfg\.set\s*\(\s*(?=\{)""").findAll(page).mapNotNull { match ->
        runCatching { (JSONTokener(page.substring(match.range.last + 1)).nextValue() as? JSONObject)?.opt("LOGGED_IN") as? Boolean }.getOrNull()
    }.toSet()
    return when(flags.singleOrNull()) {
        true -> AccountStatus.VALID
        false -> AccountStatus.EXPIRED
        null -> AccountStatus.UNKNOWN
    }
}

internal fun youtubePlayerResponse(page: String): JSONObject? {
    val match = Regex("""(?:var\s+ytInitialPlayerResponse\s*=|window\["ytInitialPlayerResponse"\]\s*=)\s*(?=\{)""").find(page) ?: return null
    return runCatching { JSONTokener(page.substring(match.range.last + 1)).nextValue() as? JSONObject }.getOrNull()
}

/** Authenticated web player data is preferred; missing playable formats can fall back to public extraction. */
internal fun youtubeWebVideo(page: String, link: VideoLink, decode: (String, String) -> String = ::decodeYoutubeUrl): VideoInfo? {
    val player = youtubePlayerResponse(page) ?: return null
    val status = player.optJSONObject("playabilityStatus")?.optString("status")
    if(status != "OK") return null
    val details = player.optJSONObject("videoDetails") ?: return null
    check(details.optString("videoId") == link.key.removePrefix("yt:")) { "YouTube 返回的视频与链接不一致" }
    check(!details.optBoolean("isLive")) { "暂不支持 YouTube 直播" }
    val data = player.optJSONObject("streamingData") ?: return null
    fun formats(name: String) = data.optJSONArray(name)?.let { array ->
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }.filter {
            it.optJSONArray("drmFamilies").let { drm -> drm == null || drm.length() == 0 }
        }
    }.orEmpty()
    val adaptive = formats("adaptiveFormats")
    val audio = adaptive.filter { it.optString("mimeType").startsWith("audio/mp4") && it.optString("mimeType").contains("mp4a") }
        .maxWithOrNull(compareBy<JSONObject> { it.optJSONObject("audioTrack")?.optBoolean("audioIsDefault") == true }
            .thenBy { it.optLong("averageBitrate").takeIf { rate -> rate > 0 } ?: it.optLong("bitrate") }
            .thenBy { it.optLong("bitrate") })
    val combined = formats("formats")
    val video = (combined + if(audio != null) adaptive else emptyList()).filter {
        val mime = it.optString("mimeType")
        mime.startsWith("video/mp4") && listOf("avc1", "hev1", "hvc1").any(mime::contains)
    }.maxWithOrNull(compareBy<JSONObject> { it.optLong("width") * it.optLong("height") }
        .thenBy { it.optInt("fps") }.thenBy { it.optLong("bitrate") }) ?: return null
    val id = details.getString("videoId")
    fun stream(format: JSONObject): String {
        val direct = format.optString("url")
        val raw = direct.ifEmpty { format.optString("signatureCipher").ifEmpty { format.optString("cipher") } }
        check(raw.isNotBlank()) { "YouTube 未返回下载地址" }
        return decode(id, raw).also { value ->
            val url = value.toHttpUrlOrNull()
            check(url != null && url.isHttps && (url.host == "googlevideo.com" || url.host.endsWith(".googlevideo.com"))) { "YouTube 媒体地址无效" }
        }
    }
    return VideoInfo(link, link.key, details.optString("title", "YouTube $id"), stream(video),
        audio = audio?.let(::stream), quality = video.optString("qualityLabel"),
        referer = link.url, userAgent = VideoResolver.DESKTOP,
        resolution = resolutionLabel(video.optInt("width"), video.optInt("height")))
}

private fun decodeYoutubeUrl(id: String, raw: String): String {
    val direct = if(raw.startsWith("https://")) raw else {
        val parts = raw.split('&').associate { part ->
            val pair = part.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        }
        val url = parts["url"]?.toHttpUrlOrNull() ?: error("YouTube 视频签名格式无效")
        url.newBuilder().addQueryParameter(parts["sp"] ?: "signature",
            YoutubeJavaScriptPlayerManager.deobfuscateSignature(id, parts["s"].orEmpty())).build().toString()
    }
    return YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(id, direct)
}
