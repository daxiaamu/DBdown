package com.daxiaamu.dbdown

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class SlidesOnlineTest {
    @Test fun userSampleHasEightDownloadableLiveParts() {
        assumeTrue(System.getenv("DBDOWN_SLIDES_ONLINE") == "1")
        val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        val url = "https://www.iesdouyin.com/web/api/v2/aweme/slidesinfo/?aweme_ids=%5B7688970467424551275%5D&request_source=200"
        val raw = client.newCall(Request.Builder().url(url).header("User-Agent", VideoResolver.MOBILE).build()).execute().use {
            assertTrue(it.isSuccessful); it.body!!.string()
        }
        val info = DouyinPage.parseSlides(raw, Links.detect("https://www.douyin.com/slides/7688970467424551275")!!)
        assertEquals(8, info.images.size)
        assertEquals(8, info.imageVideos.filterNotNull().size)
        assertNotNull(info.music)
        (info.images + info.imageVideos.filterNotNull() + listOfNotNull(info.music)).forEachIndexed { index, media ->
            client.newCall(Request.Builder().url(media).header("User-Agent", VideoResolver.MOBILE)
                .header("Referer", info.referer).header("Range", "bytes=0-31").build()).execute().use {
                assertTrue("Resource $index: HTTP ${it.code}", it.isSuccessful)
                assertTrue(it.body!!.byteStream().read(ByteArray(16)) > 0)
            }
        }
        println("Verified 8 still images, 8 live videos, and separate music")
    }
}
