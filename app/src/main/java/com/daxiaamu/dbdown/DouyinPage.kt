package com.daxiaamu.dbdown

import org.json.JSONArray
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Reads only the current work, never recommendations or cover images. */
internal object DouyinPage {
    fun parse(page: String, link: VideoLink): VideoInfo {
        val raw = Regex("""window\._ROUTER_DATA\s*=\s*(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(page)?.groupValues?.get(1)?.trim()?.trimEnd(';')
            ?: error("抖音未返回作品数据，可能需要验证，请稍后重试")
        val loaders = JSONObject(raw).getJSONObject("loaderData")
        val loader = loaders.optJSONObject("note_(id)/page") ?: loaders.optJSONObject("video_(id)/page")
            ?: error("该链接不是支持的抖音作品")
        val items = loader.optJSONObject("videoInfoRes")?.optJSONArray("item_list")
        check(items != null && items.length() > 0) { "作品不存在、已删除或当前无法访问" }
        val id = link.key.removePrefix("dy:")
        val item = (0 until items.length()).map { items.getJSONObject(it) }
            .firstOrNull { it.optString("aweme_id") == id } ?: error("平台返回的作品与分享链接不一致")
        val images = item.optJSONArray("images")
        val title = item.optString("desc").ifBlank { "抖音作品 $id" }
        val play = item.optJSONObject("video")?.optJSONObject("play_addr")
        if(images != null && images.length() > 0) {
            val urls = (0 until images.length()).map { index ->
                val image = images.getJSONObject(index)
                firstUrl(image.optJSONArray("url_list")) ?: error("第 ${index + 1} 张图片没有可用地址")
            }
            val music = firstUrl(item.optJSONObject("music")?.optJSONObject("play_url")?.optJSONArray("url_list"))
                ?: validUrl(play?.optString("uri").orEmpty())
                ?: firstUrl(play?.optJSONArray("url_list"))?.replace("/playwm/", "/play/")
            val canonical = link.copy(url = "https://www.douyin.com/note/$id")
            return VideoInfo(canonical, "dy:$id", title, "", quality = "${urls.size} 张图片",
                referer = "https://www.douyin.com/", userAgent = VideoResolver.MOBILE, images = urls, music = music)
        }
        val url = firstUrl(play?.optJSONArray("url_list")) ?: error("没有可用的视频地址")
        return VideoInfo(link, "dy:$id", title, url.replace("/playwm/", "/play/"), quality = "原视频",
            referer = "https://www.douyin.com/", userAgent = VideoResolver.MOBILE)
    }
    private fun firstUrl(array: JSONArray?): String? = array?.let {
        (0 until it.length()).firstNotNullOfOrNull { i -> validUrl(it.optString(i)) }
    }
    private fun validUrl(raw: String): String? {
        val url = raw.replaceFirst(Regex("^http://"), "https://").toHttpUrlOrNull() ?: return null
        return url.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }?.toString()
    }
}
