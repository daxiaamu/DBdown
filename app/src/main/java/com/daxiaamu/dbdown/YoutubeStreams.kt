package com.daxiaamu.dbdown

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** One immutable input track; a null plan means a regular HTTP resource. */
data class MediaSegment(val url: String, val start: Long = 0, val length: Long = -1,
    val keyUrl: String? = null, val iv: String? = null)
data class SegmentPlan(val segments: List<MediaSegment>, val identity: String)

internal enum class YoutubeProtocol { HTTP, DASH, HLS }
internal data class YoutubeStream(
    val url: String, val video: Boolean, val audio: Boolean, val mime: String,
    val width: Int = 0, val height: Int = 0, val fps: Float = 0f, val hdr: Boolean = false,
    val bitrate: Long = 0, val language: String = "", val original: Boolean = false,
    val defaultAudio: Boolean = false, val channels: Int = 0,
    val userAgent: String = VideoResolver.DESKTOP, val source: String = "player",
    val formatId: String = "", val protocol: YoutubeProtocol = YoutubeProtocol.HTTP,
    val plan: SegmentPlan? = null, val durationMs: Long = 0
) {
    val audioCodec get() = youtubeAudioCodec(mime)
}
internal val youtubeVideoOrder = compareBy<YoutubeStream> { it.width.toLong() * it.height }
    .thenBy { it.fps }.thenBy { it.hdr }.thenBy { it.bitrate }
internal val youtubeAudioOrder = compareBy<YoutubeStream> { it.original }.thenBy { it.defaultAudio || it.language.isBlank() }
    .thenBy { it.channels }.thenBy { it.bitrate }

internal fun youtubeMediaUrl(value: String): Boolean = value.toHttpUrlOrNull()?.let {
    it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.port == 443 &&
        (it.host == "googlevideo.com" || it.host.endsWith(".googlevideo.com"))
} == true

internal fun youtubeMatchingPlayer(player: JSONObject, link: VideoLink): Boolean =
    player.optJSONObject("videoDetails")?.let {
        it.optString("videoId") == link.key.removePrefix("yt:") && !it.optBoolean("isLive")
    } == true && player.optJSONObject("playabilityStatus")?.optString("status") == "OK"

/** Every returned format is considered; no static itag or resolution whitelist. */
internal fun youtubeDirectStreams(player: JSONObject, link: VideoLink,
    decode: (String, String) -> String = ::decodeYoutubeUrl): List<YoutubeStream> {
    if(!youtubeMatchingPlayer(player, link)) return emptyList()
    val data = player.optJSONObject("streamingData") ?: return emptyList()
    val result = mutableListOf<YoutubeStream>()
    for(name in listOf("formats", "adaptiveFormats")) {
        val array = data.optJSONArray(name) ?: continue
        for(index in 0 until array.length()) {
            val format = array.optJSONObject(index) ?: continue
            if((format.optJSONArray("drmFamilies")?.length() ?: 0) > 0) continue
            val mime = format.optString("mimeType")
            val video = mime.startsWith("video/") && listOf("avc", "hev", "hvc", "av01", "vp9", "vp09").any(mime::contains)
            val audio = mime.startsWith("audio/") && listOf("mp4a", "opus", "ac-3", "ec-3").any(mime::contains)
            if(!video && !audio) continue
            val raw = format.optString("url").ifBlank { format.optString("signatureCipher").ifBlank { format.optString("cipher") } }
            if(raw.isBlank()) continue // SABR-only entries are not downloadable HTTP resources.
            val url = runCatching { decode(link.key.removePrefix("yt:"),raw) }.getOrNull() ?: continue
            if(!youtubeMediaUrl(url)) continue
            val track = format.optJSONObject("audioTrack")
            result += YoutubeStream(url, video, audio || name == "formats", mime,
                format.optInt("width"),format.optInt("height"),format.optDouble("fps",0.0).toFloat(),youtubeHdr(format),
                format.optLong("averageBitrate").takeIf { it > 0 } ?: format.optLong("bitrate"),
                track?.optString("id").orEmpty(),track?.optString("displayName")?.contains("original",true) == true,
                track?.optBoolean("audioIsDefault") == true,format.optInt("audioChannels"),
                player.optString("_dbdownUa",VideoResolver.DESKTOP),player.optString("_dbdownSource","player"),
                format.optString("itag"),durationMs=format.optLong("approxDurationMs"))
        }
    }
    return result
}

internal fun youtubeSelectStreams(streams: List<YoutubeStream>, prepare: (YoutubeStream) -> YoutubeStream?): Pair<YoutubeStream,YoutubeStream?>? {
    val unique = streams.distinctBy { listOf(it.url,it.formatId,it.language,it.protocol.name) }
    // A bad highest audio must not prevent a lower working original track from being used.
    val audio = unique.filter { it.audio && !it.video }.sortedWith(youtubeAudioOrder.reversed())
        .firstNotNullOfOrNull(prepare)
    val video = unique.filter { it.video && (it.audio || audio != null) }.sortedWith(youtubeVideoOrder.reversed())
        .firstNotNullOfOrNull(prepare) ?: return null
    return video to audio
}

/** The final selected tracks alone determine display metadata and the transfer plan. */
internal fun youtubeVideoInfo(link: VideoLink, title: String, selection: Pair<YoutubeStream,YoutubeStream?>): VideoInfo {
    val (video,audio) = selection
    return VideoInfo(link,link.key,title,video.url,audio=audio?.url,audioCodec=audio?.audioCodec.orEmpty(),
        quality="${video.height}p${if(video.fps>30) video.fps.toInt() else ""}${if(video.hdr) " HDR" else ""}",
        referer=link.url,userAgent=video.userAgent,resolution=resolutionLabel(video.width,video.height),
        videoPlan=video.plan,audioPlan=audio?.plan,audioUserAgent=audio?.userAgent)
}

internal fun youtubeAudioCodec(mime: String): String = when {
    mime.contains("opus") -> "opus"
    mime.contains("ec-3") -> "eac3"
    mime.contains("ac-3") -> "ac3"
    else -> "aac"
}

internal fun youtubeHdr(format: JSONObject): Boolean {
    val transfer = format.optJSONObject("colorInfo")?.optString("transferCharacteristics").orEmpty()
    return transfer.contains("SMPTEST2084") || transfer.contains("ARIB_STD_B67") ||
        format.optString("qualityLabel").contains("HDR", ignoreCase = true)
}
