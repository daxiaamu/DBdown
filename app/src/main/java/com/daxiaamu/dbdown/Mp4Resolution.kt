package com.daxiaamu.dbdown

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bounded local prefix read: no second network transfer and no source-image dimensions. */
internal fun partialVideoResolution(file: File): String = runCatching {
    val prefix = file.inputStream().use { it.readNBytes(512 * 1024) }
    mp4Resolution(prefix)
}.getOrDefault("")

internal fun mp4Resolution(bytes: ByteArray): String {
    val data = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    fun boxes(start: Int, limit: Int, depth: Int): String {
        if(depth > 3) return ""
        var offset = start
        while(offset + 8 <= limit) {
            val size = data.getInt(offset).toLong() and 0xffffffffL
            if(size < 8 || size > Int.MAX_VALUE) return ""
            val end = (offset.toLong() + size).coerceAtMost(limit.toLong()).toInt()
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            if(type == "moov" || type == "trak") {
                boxes(offset + 8, end, depth + 1).takeIf { it.isNotBlank() }?.let { return it }
            } else if(type == "tkhd" && offset.toLong() + size <= limit && size >= 92) {
                var w = data.getInt(end - 8) ushr 16
                var h = data.getInt(end - 4) ushr 16
                val a = data.getInt(end - 44)
                val b = data.getInt(end - 40)
                if(a == 0 && b != 0) { val swap = w; w = h; h = swap }
                if(w > 0 && h > 0) return "$w × $h"
            }
            if(offset.toLong() + size > limit) return ""
            offset += size.toInt()
        }
        return ""
    }
    return boxes(0, bytes.size, 0)
}
