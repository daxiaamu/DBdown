package com.daxiaamu.dbdown

internal fun mediaBaseName(title: String): String {
    val clean = title.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_").trim(' ', '.')
    val output = StringBuilder()
    var bytes = 0
    val points = clean.codePoints().iterator()
    while(points.hasNext()) {
        val codePoint = points.nextInt()
        val next = String(Character.toChars(codePoint))
        val size = next.toByteArray(Charsets.UTF_8).size
        if(bytes + size > 160) break // Leave room for suffix and MediaStore's pending prefix.
        output.append(next); bytes += size
    }
    return output.toString().trim(' ', '.').ifEmpty { "作品" }
}
