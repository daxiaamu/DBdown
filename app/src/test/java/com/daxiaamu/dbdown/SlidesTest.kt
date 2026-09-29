package com.daxiaamu.dbdown

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import javax.xml.parsers.DocumentBuilderFactory

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
            val xml = metadata.substring(metadata.indexOf("<x:xmpmeta"), metadata.indexOf("</x:xmpmeta>") + "</x:xmpmeta>".length)
            val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(xml.byteInputStream())
            val description = document.getElementsByTagNameNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "Description").item(0) as org.w3c.dom.Element
            val oplus = "http://ns.oplus.com/photos/1.0/camera/"
            assertEquals("oplus", description.getAttributeNS(oplus, "MotionPhotoOwner"))
            assertEquals("2", description.getAttributeNS(oplus, "OLivePhotoVersion"))
            assertEquals(video.size.toString(), description.getAttributeNS(oplus, "VideoLength"))
            assertEquals("1", description.getAttributeNS("http://ns.google.com/photos/1.0/camera/", "MotionPhoto"))
            assertArrayEquals(byteArrayOf(77, 80, 70, 0), bytes.copyOfRange(6, 10))
            val mpf = ByteBuffer.wrap(bytes)
            assertEquals(1, mpf.getInt(40)) // NumberOfImages
            assertEquals(50, mpf.getInt(52)) // MP entry offset relative to TIFF start at 10
            assertEquals(bytes.size - video.size, mpf.getInt(64))
            val rewritten = MotionPhoto.jpegMetadata(MotionPhoto.jpegMetadata(jpeg, 2), 3)
            assertEquals(rewritten.size, ByteBuffer.wrap(rewritten).getInt(64))
            val twice = MotionPhoto.jpegMetadata(MotionPhoto.jpegMetadata(jpeg, 2), 3).toString(Charsets.UTF_8)
            assertEquals(1, Regex("<x:xmpmeta").findAll(twice).count())
        } finally { dir.deleteRecursively() }
    }
}
