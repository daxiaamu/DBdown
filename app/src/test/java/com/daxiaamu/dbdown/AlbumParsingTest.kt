package com.daxiaamu.dbdown

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AlbumParsingTest {
    private val link = Links.detect("https://www.douyin.com/note/7689856421856364794")!!
    private fun page(item: String) = "<script>window._ROUTER_DATA = " +
        """{"loaderData":{"note_(id)/page":{"videoInfoRes":{"item_list":[$item]}}}}""" + ";</script>"
    @Test fun recognizesOriginalShareShortAndNoteLinks() {
        assertEquals("douyin-short:/QbQGcMP060w", Links.detect("分享 [https://v.douyin.com/QbQGcMP060w/](https://v.douyin.com/QbQGcMP060w/)")?.key)
        assertEquals(link.key, Links.detect("https://www.iesdouyin.com/share/note/7689856421856364794/")?.key)
        assertNull(Links.detect("https://www.douyin.com.evil.test/note/7689856421856364794/"))
    }
    @Test fun preservesImageOrderAndExtractsActualSoundtrack() {
        val info = DouyinPage.parse(page("""{"aweme_id":"7689856421856364794","desc":"图集","images":[
            {"url_list":["https://images.example.com/1.webp"]}, {"url_list":["https://images.example.com/2.webp"]}],
            "video":{"play_addr":{"uri":"https://music.example.com/music.mp3"},"cover":{"url_list":["https://images.example.com/cover.jpg"]}}} """), link)
        assertEquals(listOf("https://images.example.com/1.webp", "https://images.example.com/2.webp"), info.images)
        assertEquals("https://music.example.com/music.mp3", info.music)
        assertEquals("", info.video)
    }
    @Test fun missingMusicRemainsSilentAndMissingImagesFail() {
        val item = JSONObject("""{"aweme_id":"7689856421856364794","images":[{"url_list":["https://images.example.com/1.png"]}]}""")
        item.put("video", JSONObject().put("play_addr", JSONObject().put("uri", "https://example.com/slideshow.mp4")))
        assertNull(DouyinPage.parse(page(item.toString()), link).music)
        item.put("music", JSONObject().put("play_url", JSONObject().put("url_list", org.json.JSONArray().put("https://example.com/original.mp3"))))
        assertEquals("https://example.com/original.mp3", DouyinPage.parse(page(item.toString()), link).music)
        item.getJSONArray("images").getJSONObject(0).put("url_list", org.json.JSONArray("[\"file:///private/image\"]"))
        assertTrue(runCatching { DouyinPage.parse(page(item.toString()), link) }.isFailure)
    }
    @Test fun musicUriWithoutExtensionIsPreserved() {
        val item = JSONObject("""{"aweme_id":"7689856421856364794","images":[{"url_list":["https://images.example.com/1.png"]}],
            "music":{"play_url":{"uri":"https://music.example.com/original?token=valid"}}}""")
        assertEquals("https://music.example.com/original?token=valid", DouyinPage.parse(page(item.toString()), link).music)
        item.remove("music")
        item.put("video", JSONObject("""{"play_addr":{"url_list":["https://cdn.example.com/obj/ies-music/12345"]}}"""))
        assertEquals("https://cdn.example.com/obj/ies-music/12345", DouyinPage.parse(page(item.toString()), link).music)
    }
    @Test fun selectsOriginalThenBestQualityWithoutWatermarkAndPreservesSignatures() {
        val image = JSONObject("""{"uri":"original-id","url_list":[
            "https://cdn.example.com/img~tplv-dy-aweme-images:q75.webp?sig=display",
            "https://cdn.example.com/img~tplv-dy-aweme-images:q100.webp?sig=high"],
            "download_url_list":["https://cdn.example.com/img~tplv-dy-water-v2:user:1920:1440.webp?sig=water"]}""")
        assertEquals("https://cdn.example.com/img~tplv-dy-aweme-images:q100.webp?sig=high", DouyinPage.bestImageUrl(image))
        image.put("video", JSONObject("""{"cover":{"uri":"original-id","url_list":["https://cdn.example.com/obj/original?sig=original"]}}"""))
        assertEquals("https://cdn.example.com/obj/original?sig=original", DouyinPage.bestImageUrl(image))
        image.getJSONObject("video").getJSONObject("cover").put("uri", "different-cover")
        assertTrue(DouyinPage.bestImageUrl(image)!!.contains("q100"))
        image.remove("url_list"); image.remove("video")
        assertNull(DouyinPage.bestImageUrl(image))
    }
    @Test fun neverUsesRecommendedDifferentWork() {
        assertTrue(runCatching { DouyinPage.parse(page("""{"aweme_id":"9999999999999999999","images":[{"url_list":["https://images.example.com/x"]}]}"""), link) }.isFailure)
    }
    @Test fun longUnicodeTitlesRespectFilesystemByteLimit() {
        val name = mediaBaseName("中文标题😀".repeat(100))
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 160)
        assertEquals(name, name.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
        assertEquals("作品", mediaBaseName("..."))
        assertFalse(mediaBaseName("a/b:c").contains('/'))
    }

}
