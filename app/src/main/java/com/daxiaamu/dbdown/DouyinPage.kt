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
    private data class VideoVariant(val pixels: Long, val bitrate: Long, val addresses: List<String>)
    private val qualityOrder = compareByDescending<VideoVariant> { it.pixels }.thenByDescending { it.bitrate }

    private fun mobileVariants(video: JSONObject): List<VideoVariant> {
        val rates = video.optJSONArray("bit_rate")
        return (0 until (rates?.length() ?: 0)).mapNotNull { rates!!.optJSONObject(it) }.map { rate ->
            val stream = rate.optJSONObject("play_addr")
            VideoVariant((stream?.optLong("width") ?: 0) * (stream?.optLong("height") ?: 0),
                rate.optLong("bit_rate"), urls(stream?.optJSONArray("url_list")).map { it.replace("/playwm/", "/play/") })
        }
    }

    private fun desktopVariants(raw: String, link: VideoLink): Pair<List<VideoVariant>, List<String>> {
        val item = JSONObject(raw)
        check(item.optString("awemeId") == link.key.removePrefix("dy:")) { "网页返回的作品与分享链接不一致" }
        check((item.optJSONArray("images")?.length() ?: 0) == 0) { "作品类型发生变化，请重试" }
        val video = item.getJSONObject("video")
        fun addresses(array: JSONArray?): List<String> = (0 until (array?.length() ?: 0)).mapNotNull {
            validUrl(array!!.optJSONObject(it)?.optString("src").orEmpty())
        }
        val rates = video.optJSONArray("bitRateList")
        val variants = (0 until (rates?.length() ?: 0)).map { rates!!.getJSONObject(it) }
            .filter { it.optString("format").let { format -> format.isBlank() || format == "mp4" } }
            .map { VideoVariant(it.optLong("width") * it.optLong("height"), it.optLong("bitRate"), addresses(it.optJSONArray("playAddr"))) }
        return variants to addresses(video.optJSONArray("playAddr"))
    }

    fun desktopVideoUrls(raw: String, link: VideoLink): List<String> {
        val (variants, fallback) = desktopVariants(raw, link)
        return (variants.sortedWith(qualityOrder).flatMap { it.addresses } + fallback).distinct().also {
            check(it.isNotEmpty()) { "网页没有返回可下载的视频地址" }
        }
    }

    /** Compare actual variants from both responses; top-level dimensions describe the upload, not the stream. */
    fun supplementVideo(page: String, desktop: String, info: VideoInfo): VideoInfo {
        if(info.images.isNotEmpty()) return info
        val (web, fallback) = desktopVariants(desktop, info.source)
        val mobile = mobileVariants(shareItem(page, info.source).getJSONObject("video"))
        val candidates = ((mobile + web).sortedWith(qualityOrder).flatMap { it.addresses } +
            listOf(info.video) + info.videoFallbacks + fallback).distinct()
        return info.copy(video = candidates.first(), videoFallbacks = candidates.drop(1))
    }

    fun needsDesktopLive(raw: String, link: VideoLink): Boolean {
        val items = JSONObject(raw).optJSONArray("aweme_details") ?: return false
        val item = (0 until items.length()).map { items.getJSONObject(it) }
            .firstOrNull { it.optString("aweme_id") == link.key.removePrefix("dy:") } ?: return false
        val images = item.optJSONArray("images") ?: return false
        return (0 until images.length()).any { index ->
            val image = images.getJSONObject(index)
            image.optJSONObject("video") == null &&
                ((image.optJSONObject("resolution_log_param")?.optInt("video_source_height") ?: 0) > 0 ||
                    image.optInt("clip_type") in setOf(3, 5) || image.optInt("live_photo_type") == 1)
        }
    }

    fun supplementDesktop(raw: String, desktop: String, link: VideoLink): VideoInfo {
        val mobile = JSONObject(raw)
        val items = mobile.getJSONArray("aweme_details")
        val item = (0 until items.length()).map { items.getJSONObject(it) }
            .first { it.optString("aweme_id") == link.key.removePrefix("dy:") }
        val web = JSONObject(desktop)
        check(web.optString("awemeId") == item.getString("aweme_id")) { "网页返回的作品与分享链接不一致" }
        val images = item.getJSONArray("images")
        val webImages = web.getJSONArray("images")
        check(images.length() == webImages.length()) { "网页图集不完整，请重试" }
        for(index in 0 until images.length()) {
            val image = images.getJSONObject(index)
            val webImage = webImages.getJSONObject(index)
            check(image.getString("uri") == webImage.getString("uri")) { "网页图片与分享内容不一致，请重试" }
            val live = webImage.optInt("livePhotoType") == 1 || webImage.optInt("clipType") in setOf(3, 5)
            val video = webImage.optJSONObject("video")
            if(live) {
                val addresses = video?.optJSONArray("playAddr")
                val urls = JSONArray()
                for(i in 0 until (addresses?.length() ?: 0)) {
                    validUrl(addresses!!.optJSONObject(i)?.optString("src").orEmpty())?.let { urls.put(it) }
                }
                check(urls.length() > 0) { "第 ${index + 1} 张实况图缺少动态资源，请重试" }
                val normalized = JSONObject().put("play_addr", JSONObject().put("url_list", urls))
                if(video?.optString("coverUri") == image.optString("uri")) {
                    val covers = JSONArray()
                    validUrl(video?.optString("originCover").orEmpty())?.let { covers.put(it) }
                    normalized.put("cover", JSONObject().put("uri", image.getString("uri")).put("url_list", covers))
                }
                image.put("clip_type", 3).put("video", normalized)
            }
        }
        return parseSlides(mobile.toString(), link)
    }

    private fun shareItem(page: String, link: VideoLink): JSONObject {
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
        return item
    }

    fun parse(page: String, link: VideoLink): VideoInfo {
        val item = shareItem(page, link)
        val id = link.key.removePrefix("dy:")
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
            val audioCandidates = (listOfNotNull(validUrl(play?.optString("uri").orEmpty())) + urls(play?.optJSONArray("url_list")))
                .map { url -> validUrl(url.toHttpUrlOrNull()?.queryParameter("video_id").orEmpty()) ?: url }.distinct()
            val imageVideos = (0 until images.length()).map { index ->
                val image = images.getJSONObject(index)
                val video = image.optJSONObject("video")
                val url = firstUrl(video?.optJSONObject("play_addr")?.optJSONArray("url_list"))
                    ?: firstUrl(video?.optJSONObject("play_addr_h264")?.optJSONArray("url_list"))
                check((image.optInt("clip_type") !in setOf(3, 5) && image.optInt("live_photo_type") != 1) || url != null) { "第 ${index + 1} 张 Live 图缺少动态资源" }
                url
            }
            val kind = if(link.url.contains("/slides/")) "slides" else "note"
            val canonical = link.copy(url = "https://www.douyin.com/$kind/$id")
            return VideoInfo(canonical, "dy:$id", title, "", quality = "${urls.size} 张图片",
                referer = "https://www.douyin.com/", userAgent = VideoResolver.MOBILE, images = urls, music = music, imageVideos = imageVideos, separateAlbumMusic = kind == "slides" || imageVideos.any { it != null }, musicCandidates = audioCandidates)
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
        val ranked = mobileVariants(video).sortedWith(qualityOrder).flatMap { it.addresses }
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
