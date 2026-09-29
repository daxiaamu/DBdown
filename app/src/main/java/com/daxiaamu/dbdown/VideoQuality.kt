package com.daxiaamu.dbdown

import org.json.JSONArray
import org.json.JSONObject

/** Select only returned, muxable streams; never infer access from advertised quality names. */
internal fun bestBiliVideo(array: JSONArray): JSONObject? =
    (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        .filter { stream -> listOf("avc", "hev", "hvc").any { stream.optString("codecs").startsWith(it) } }
        .maxWithOrNull(compareBy<JSONObject> { it.optLong("width") * it.optLong("height") }
            .thenBy { it.optInt("id") }.thenBy { it.optLong("bandwidth") })

/** Prefer returned lossless FLAC; otherwise copy the highest bitrate AAC track. */
internal fun bestBiliAudio(dash: JSONObject): JSONObject? {
    val streams = buildList<JSONObject> {
        fun addArray(array: JSONArray?) {
            if(array != null) for(index in 0 until array.length()) array.optJSONObject(index)?.let { add(it) }
        }
        addArray(dash.optJSONArray("audio"))
        addArray(dash.optJSONObject("dolby")?.optJSONArray("audio"))
        dash.optJSONObject("flac")?.optJSONObject("audio")?.let { add(it) }
    }
    return streams.filter { it.optString("codecs").startsWith("mp4a.40.") || it.optString("codecs").equals("flac", true) }
        .filter { it.optString("baseUrl").isNotBlank() || it.optString("base_url").isNotBlank() }
        .maxWithOrNull(compareBy<JSONObject> { it.optString("codecs").equals("flac", true) }
            .thenBy { it.optLong("bandwidth") }.thenBy { it.optInt("id") })
}
