package com.daxiaamu.dbdown

import java.util.Locale

data class TrackSelection(val video: String, val audio: String? = null)
data class TrackOption(val id: String, val label: String, val detail: String = "", val hasAudio: Boolean = false)
data class MediaSpecifications(val videos: List<TrackOption>, val audios: List<TrackOption>, val selected: TrackSelection)
internal fun frameRate(value: String): Float {
    val parts = value.split('/')
    val rate = if(parts.size == 2) (parts[0].toFloatOrNull() ?: 0f) / (parts[1].toFloatOrNull() ?: 0f) else value.toFloatOrNull() ?: 0f
    return rate.takeIf { it.isFinite() && it > 0 } ?: 0f
}
internal fun fpsLabel(fps: Float): String = if(fps > 0 && fps.isFinite())
    String.format(Locale.ROOT,"%.2f",fps).trimEnd('0').trimEnd('.') + " fps" else ""
internal fun videoSpecification(resolution: String, fps: Float) =
    listOf(resolution.ifBlank { "选择规格" },fpsLabel(fps)).filter(String::isNotBlank).joinToString(" · ")
internal fun codecLabel(codec: String) = when {
    codec.contains("av01",true) -> "AV1"
    codec.contains("vp9",true) || codec.contains("vp09",true) -> "VP9"
    codec.contains("hev",true) || codec.contains("hvc",true) -> "HEVC"
    codec.contains("avc",true) -> "AVC"
    codec.contains("flac",true) -> "FLAC 无损"
    codec.contains("opus",true) -> "Opus"
    codec.contains("mp4a",true) -> "AAC"
    else -> codec.ifBlank { "原始编码" }
}
internal fun bitrateLabel(bitrate: Long) = if(bitrate > 0) "${bitrate / 1000} kbps" else ""

data class DirectVideoTrack(val id: String, val width: Int, val height: Int, val fps: Float,
    val codec: String, val bitrate: Long, val urls: List<String>)

/** Embedded audio cannot be replaced through the specification picker. */
internal fun MediaSpecifications.selectVideo(id: String, previous: TrackSelection? = null): TrackSelection {
    val video = videos.first { it.id == id }
    return TrackSelection(id, if(video.hasAudio) null else previous?.audio?.takeIf { audio -> audios.any { it.id == audio } })
}
