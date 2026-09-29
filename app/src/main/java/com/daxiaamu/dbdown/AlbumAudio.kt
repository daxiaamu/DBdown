package com.daxiaamu.dbdown

/** A URL's extension/path is not evidence of its media type. Inspect the bounded response header. */
internal fun isAlbumAudio(header: ByteArray, contentType: String): Boolean {
    val type = contentType.substringBefore(';').trim().lowercase()
    val prefix = header.toString(Charsets.ISO_8859_1)
    if(type.startsWith("text/") || type.contains("json")) return false
    if(prefix.startsWith("ID3") || prefix.startsWith("fLaC")) return true
    if(header.size >= 12 && prefix.substring(4, 8) == "ftyp") {
        return prefix.substring(8, 12) in setOf("M4A ", "M4B ", "M4P ")
    }
    if(type.startsWith("video/")) return false
    // Do not accept a generic MP4 or Ogg video simply because it is stored in a music-like URL.
    return type.startsWith("audio/") && header.isNotEmpty() && !prefix.startsWith("<")
}
