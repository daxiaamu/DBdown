package com.daxiaamu.dbdown

import org.json.JSONArray
import org.json.JSONObject

/** Select only returned, muxable streams; never infer access from advertised quality names. */
internal fun bestBiliVideo(array: JSONArray): JSONObject? =
    (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        .filter { stream -> listOf("avc", "hev", "hvc").any { stream.optString("codecs").startsWith(it) } }
        .maxWithOrNull(compareBy<JSONObject> { it.optLong("width") * it.optLong("height") }
            .thenBy { it.optInt("id") }.thenBy { it.optLong("bandwidth") })
