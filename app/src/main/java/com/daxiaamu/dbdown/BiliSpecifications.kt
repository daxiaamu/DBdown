package com.daxiaamu.dbdown

import org.json.JSONObject

internal fun biliTrackId(stream: JSONObject) = "bili:${stream.optInt("id")}:${stream.optString("codecs")}:${stream.optInt("width")}:${stream.optInt("height")}:${stream.optString("frame_rate").ifBlank { stream.optString("frameRate") }}"
internal fun biliVideoInfo(link: VideoLink, title: String, dash: JSONObject, requested: TrackSelection?): VideoInfo {
    val array = dash.getJSONArray("video")
    val videos = (0 until array.length()).map { array.getJSONObject(it) }.filter { stream ->
        listOf("avc","hev","hvc").any { stream.optString("codecs").startsWith(it) }
    }.sortedWith(compareByDescending<JSONObject> { it.optLong("width") * it.optLong("height") }
        .thenByDescending { it.optLong("bandwidth") }).distinctBy(::biliTrackId)
    val audios = biliAudioStreams(dash).distinctBy(::biliTrackId)
    val video = if(requested == null) bestBiliVideo(array) else videos.find { biliTrackId(it) == requested.video }
    val audio = if(requested == null) audios.firstOrNull() else audios.find { biliTrackId(it) == requested.audio }
    check(video != null && (audio != null || (requested != null && requested.audio == null))) { "所选音视频规格已不可用，请重新选择" }
    fun rate(s: JSONObject) = frameRate(s.optString("frame_rate").ifBlank { s.optString("frameRate") })
    fun url(s: JSONObject) = s.optString("baseUrl").ifBlank { s.getString("base_url") }.replaceFirst(Regex("^http://"),"https://")
    val specs = MediaSpecifications(videos.map {
        TrackOption(biliTrackId(it), videoSpecification(resolutionLabel(it.optInt("width"),it.optInt("height")),rate(it)),
            listOf(codecLabel(it.optString("codecs")),bitrateLabel(it.optLong("bandwidth"))).filter(String::isNotBlank).joinToString(" · "))
    }, audios.map {
        TrackOption(biliTrackId(it),codecLabel(it.optString("codecs")),bitrateLabel(it.optLong("bandwidth")))
    },TrackSelection(biliTrackId(video),audio?.let(::biliTrackId)))
    return VideoInfo(link,link.key,title,url(video),audio?.let(::url),"${video.optInt("height")}P",link.url,VideoResolver.DESKTOP,
        resolution=resolutionLabel(video.optInt("width"),video.optInt("height")),fps=rate(video),audioCodec=audio?.optString("codecs").orEmpty(),
        videoFallbacks=biliBackupUrls(video),audioFallbacks=audio?.let(::biliBackupUrls).orEmpty(),specifications=specs)
}
