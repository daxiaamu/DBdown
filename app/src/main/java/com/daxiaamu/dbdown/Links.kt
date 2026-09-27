package com.daxiaamu.dbdown

import java.net.URI

enum class Platform(val label: String) { BILI("B 站"), DOUYIN("抖音") }
data class VideoLink(val platform: Platform, val url: String, val key: String, val part: Int = 1)


object Links {
    private val urls = Regex("""(?i)(?:https?://)?(?:[a-z0-9-]+\.)+(?:com|cn|tv)/[^\s<>"\[\](){}，。！？、【】（）]+""")
    private val bv = Regex("""(?<![A-Za-z0-9])BV[1-9A-HJ-NP-Za-km-z]{10}(?![A-Za-z0-9])""")
    private val av = Regex("""(?i)^av([1-9][0-9]{0,18})$""")
    fun detect(text: String): VideoLink? {
        val input = text.take(16000).trim()
        for (match in urls.findAll(input)) {
            fromUrl(match.value.trimEnd('.', ',', ';', '!', '?', ')', ']', '}', '。', '，', '；', '！', '？'))?.let { return it }
        }
        if (input.contains("://") || input.contains(".com")) return null
        bv.find(input)?.value?.let { return VideoLink(Platform.BILI, "https://www.bilibili.com/video/$it", "$it:p1") }
        av.matchEntire(input)?.groupValues?.get(1)?.let {
            return VideoLink(Platform.BILI, "https://www.bilibili.com/video/av$it", "av$it:p1")
        }
        return null
    }
    fun fromUrl(raw: String): VideoLink? = runCatching {
        val uri = URI(if (raw.startsWith("http", true)) raw else "https://$raw")
        if (uri.scheme !in listOf("http", "https") || uri.userInfo != null || uri.port !in listOf(-1, 443, 80)) return null
        val host = uri.host?.lowercase() ?: return null
        val path = uri.path.orEmpty()
        val part = uri.rawQuery.orEmpty().split("&").firstOrNull { it.startsWith("p=") }
            ?.substringAfter("=")?.toIntOrNull()?.coerceIn(1, 10000) ?: 1
        if (host in setOf("b23.tv", "bili2233.cn") && Regex("^/[A-Za-z0-9]+/?$").matches(path)) {
            return VideoLink(Platform.BILI, "https://$host${path.trimEnd('/')}", "$host${path.trimEnd('/')}")
        }
        if (host in setOf("bilibili.com", "www.bilibili.com", "m.bilibili.com")) {
            val id = Regex("""^/video/(BV[1-9A-HJ-NP-Za-km-z]{10}|av[1-9][0-9]{0,18})/?$""").matchEntire(path)?.groupValues?.get(1) ?: return null
            return VideoLink(Platform.BILI, "https://www.bilibili.com/video/$id?p=$part", "$id:p$part", part)
        }
        if (host == "v.douyin.com" && Regex("^/[A-Za-z0-9_-]+/?$").matches(path)) {
            return VideoLink(Platform.DOUYIN, "https://v.douyin.com${path.trimEnd('/')}/", "douyin-short:${path.trimEnd('/')}")
        }
        if (host in setOf("douyin.com", "www.douyin.com", "m.douyin.com", "www.iesdouyin.com", "iesdouyin.com")) {
            val match = Regex("""^/(?:share/)?(video|note)/([0-9]{8,22})/?$""").matchEntire(path) ?: return null
            val kind = match.groupValues[1]; val id = match.groupValues[2]
            return VideoLink(Platform.DOUYIN, "https://www.douyin.com/$kind/$id", "dy:$id")
        }
        null
    }.getOrNull()
}

class PromptGate(private val handled: MutableSet<String> = linkedSetOf()) {
    fun shouldShow(key: String) = key !in handled
    fun handled(key: String) { handled.add(key); if (handled.size > 100) handled.remove(handled.first()) }
    fun keys(): Set<String> = handled.toSet()
}
