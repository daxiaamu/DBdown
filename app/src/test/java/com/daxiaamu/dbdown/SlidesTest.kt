package com.daxiaamu.dbdown

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SlidesTest {
    private val link = Links.detect("https://www.iesdouyin.com/share/slides/7688970467424551275/?is_slides=1")!!
    private fun image(index: Int, live: Boolean) = JSONObject().put("url_list", JSONArray().put("https://example.com/$index.webp"))
        .apply { if(live) put("clip_type", 3).put("video", JSONObject().put("play_addr", JSONObject()
            .put("url_list", JSONArray().put("https://example.com/$index.mp4")))) }
    @Test fun allEightLiveImagesKeepTheirMatchingVideosAndSeparateMusic() {
        val images = JSONArray(); repeat(8) { images.put(image(it, true)) }
        val raw = JSONObject().put("status_code", 0).put("aweme_details", JSONArray().put(JSONObject()
            .put("aweme_id", "7688970467424551275").put("images", images).put("music", JSONObject()
                .put("play_url", JSONObject().put("url_list", JSONArray().put("https://example.com/music.mp3"))))))
        val info = DouyinPage.parseSlides(raw.toString(), link)
        assertEquals(8, info.images.size)
        assertEquals((0..7).map { "https://example.com/$it.mp4" }, info.imageVideos)
        assertTrue(info.separateAlbumMusic)
        assertEquals("https://example.com/music.mp3", info.music)
        assertTrue(info.source.url.contains("/slides/"))
        assertTrue(runCatching { DouyinPage.parseSlides(raw.toString(), link.copy(key = "dy:1234567890")) }.isFailure)
    }
    @Test fun motionPhotoPreservesJpegAndExactVideoBytes() {
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())
        val video = "test video bytes".toByteArray()
        val dir = kotlin.io.path.createTempDirectory().toFile()
        try {
            val source = File(dir, "image.jpg").apply { writeBytes(jpeg) }
            val clip = File(dir, "video.mp4").apply { writeBytes(video) }
            val output = File(dir, "output_MP.jpg")
            MotionPhoto.write(source, clip, output)
            val bytes = output.readBytes()
            assertArrayEquals(video, bytes.takeLast(video.size).toByteArray())
            val metadata = bytes.toString(Charsets.UTF_8)
            assertTrue(metadata.contains("Item:Semantic=\"MotionPhoto\""))
            assertTrue(metadata.contains("Item:Length=\"${video.size}\""))
            val twice = MotionPhoto.jpegMetadata(MotionPhoto.jpegMetadata(jpeg, 2), 3).toString(Charsets.UTF_8)
            assertEquals(1, Regex("<x:xmpmeta").findAll(twice).count())
        } finally { dir.deleteRecursively() }
    }
}
