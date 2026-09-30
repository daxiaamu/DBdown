package com.daxiaamu.dbdown

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class AlbumIntegrationTest {
    @Test fun legacyVideoRequestResolvesToOriginalImagesAndMusic() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "album-originals-${System.nanoTime()}"
        try {
            val store = DownloadStore(context, name)
            val link = Links.detect("https://www.douyin.com/note/7689856421856364997")!!
            val task = store.add(link, AlbumMode.VIDEO)
            val info = VideoInfo(link, link.key, "Original album", "", referer = "", userAgent = "",
                images = listOf("https://example.com/original.webp"), music = "https://example.com/music.mp3")
            assertTrue(store.markResolved(task.id, info))
            assertEquals(AlbumMode.IMAGES, store.get(task.id)!!.albumMode)
            assertTrue(store.get(task.id)!!.quality.contains("图片和配乐"))
            assertEquals(AlbumMode.IMAGES, DownloadStore(context, name).get(task.id)!!.albumMode)
        } finally { context.getSharedPreferences(name, 0).edit().clear().commit() }
    }
}
