package com.daxiaamu.dbdown

import org.json.JSONArray
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Reads only the current work, never recommendations or cover images. */
internal object DouyinPage {
    fun parseSlides(raw: String, link: VideoLink): VideoInfo {
        val json = JSONObject(raw)
        check(json.optInt("status_code", -1) == 0) { "抖音未返回 Live 图资源，请稍后重试" }
        val items = json.optJSONArray("aweme_details") ?: error("没有可用的 Live 图内容")
        val router = JSONObject().put("loaderData", JSONObject().put("note_(id)/page",
            JSONObject().put("videoInfoRes", JSONObject().put("item_list", items))))
        return parse("<script>window._ROUTER_DATA=$router</script>", link).copy(separateAlbumMusic = true)
    }
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
                bestImageUrl(image) ?: error("第 ${index + 1} 张图片没有可用的无水印地址")
            }
            val musicAddress = item.optJSONObject("music")?.optJSONObject("play_url")
            val music = firstUrl(musicAddress?.optJSONArray("url_list"))
                ?: validUrl(musicAddress?.optString("uri").orEmpty())
                ?: (listOfNotNull(validUrl(play?.optString("uri").orEmpty())) + urls(play?.optJSONArray("url_list")))
                    .firstOrNull(::isAudioAddress)
            val imageVideos = (0 until images.length()).map { index ->
                val image = images.getJSONObject(index)
                val video = image.optJSONObject("video")
                val url = firstUrl(video?.optJSONObject("play_addr")?.optJSONArray("url_list"))
                    ?: firstUrl(video?.optJSONObject("play_addr_h264")?.optJSONArray("url_list"))
                check(image.optInt("clip_type") != 3 || url != null) { "第 ${index + 1} 张 Live 图缺少动态资源" }
                url
            }
            val kind = if(link.url.contains("/slides/")) "slides" else "note"
            val canonical = link.copy(url = "https://www.douyin.com/$kind/$id")
            return VideoInfo(canonical, "dy:$id", title, "", quality = "${urls.size} 张图片",
                referer = "https://www.douyin.com/", userAgent = VideoResolver.MOBILE, images = urls, music = music, imageVideos = imageVideos, separateAlbumMusic = kind == "slides" || imageVideos.any { it != null })
        }
        val urls = play?.optJSONArray("url_list") ?: error("没有可用的视频地址")
        val originals = (0 until urls.length()).mapNotNull { validUrl(urls.optString(it)) }
            .map { it.replace("/playwm/", "/play/") }.distinct()
        check(originals.isNotEmpty()) { "没有可用的视频地址" }
        // Official web play entry may route to a different media CDN than the mobile API.
        val alternates = originals.mapNotNull { raw ->
            val url = raw.toHttpUrlOrNull()!!
            if(url.host == "aweme.snssdk.com" && url.encodedPath == "/aweme/v1/play/")
                url.newBuilder().host("www.douyin.com").build().toString() else null
        }
        val highQuality = alternates.map { raw ->
            raw.toHttpUrlOrNull()!!.newBuilder().setQueryParameter("ratio", "1080p").build().toString()
        }
        val video = item.optJSONObject("video")!!
        val bitRates = video.optJSONArray("bit_rate")
        val ranked = (0 until (bitRates?.length() ?: 0)).mapNotNull { bitRates?.optJSONObject(it) }
            .sortedWith(compareByDescending<JSONObject> {
                val stream = it.optJSONObject("play_addr")
                (stream?.optLong("width") ?: 0L) * (stream?.optLong("height") ?: 0L)
            }.thenByDescending { it.optLong("bit_rate") })
            .flatMap { entry ->
                val list = entry.optJSONObject("play_addr")?.optJSONArray("url_list")
                (0 until (list?.length() ?: 0)).mapNotNull { validUrl(list!!.optString(it)) }
            }.map { it.replace("/playwm/", "/play/") }
        val candidates = (ranked + highQuality + alternates + originals).distinct()
        return VideoInfo(link, "dy:$id", title, candidates.first(), quality = "自动画质",
            referer = "https://www.douyin.com/", userAgent = VideoResolver.MOBILE, videoFallbacks = candidates.drop(1))
    }
    internal fun bestImageUrl(image: JSONObject): String? {
        val candidates = urls(image.optJSONArray("url_list")).toMutableList()
        // Live cover addresses can point to the same original image, without the q75 display transform.
        val cover = image.optJSONObject("video")?.optJSONObject("cover")
        if(!image.optString("uri").isBlank() && cover?.optString("uri") == image.optString("uri")) {
            candidates += urls(cover.optJSONArray("url_list"))
        }
        candidates += urls(image.optJSONArray("download_url_list"))
        return candidates.distinct().filterNot { isWatermarkedImage(it) }.sortedWith(
            compareByDescending<String> { !it.toHttpUrlOrNull()!!.encodedPath.contains("~tplv-") }
                .thenByDescending { Regex("""(?:[:_-])q(\d{1,3})(?:[.:_]|$)""")
                    .find(it.toHttpUrlOrNull()!!.encodedPath)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
        ).firstOrNull()
    }
    private fun isWatermarkedImage(url: String): Boolean {
        val path = url.toHttpUrlOrNull()!!.encodedPath.lowercase()
        return "dy-water" in path || "watermark" in path
    }
    private fun isAudioAddress(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        val path = parsed.encodedPath.lowercase()
        val extension = path.substringAfterLast('.')
        return extension in setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus") ||
            (extension !in setOf("mp4", "webm", "mov", "m3u8") &&
                ("/ies-music" in path || "-music-" in parsed.host))
    }
    private fun urls(array: JSONArray?): List<String> =
        (0 until (array?.length() ?: 0)).mapNotNull { validUrl(array!!.optString(it)) }
    private fun firstUrl(array: JSONArray?): String? = array?.let {
        (0 until it.length()).firstNotNullOfOrNull { i -> validUrl(it.optString(i)) }
    }
    private fun validUrl(raw: String): String? {
        val url = raw.replaceFirst(Regex("^http://"), "https://").toHttpUrlOrNull() ?: return null
        return url.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }?.toString()
    }
}
