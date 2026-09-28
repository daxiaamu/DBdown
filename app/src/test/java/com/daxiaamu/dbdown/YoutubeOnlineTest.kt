package com.daxiaamu.dbdown

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Explicit opt-in: these checks depend on YouTube availability and are not part of offline CI. */
class YoutubeOnlineTest {
    @Test fun reportedTitleSample() = check("https://youtu.be/9j_gaUAT2yc?is=BoppiEk0ARCBg8aC")
    @Test fun normalVideo() = check("qIzGvexMjpA")
    @Test fun shorts() = check("https://www.youtube.com/shorts/-9OM3w3TWUs")
    @Test fun bareId() = check("BLKegH19KGI")
    private fun check(input: String) {
        assumeTrue(System.getenv("DBDOWN_YOUTUBE_ONLINE") == "1")
        val info = YoutubeResolver.resolve(Links.detect(input)!!) {}
        println("YouTube ${info.source.key}: ${info.title}; ${info.resolution}; separateAudio=${info.audio != null}")
        assertTrue(info.title.isNotBlank())
        assertTrue(info.resolution.isNotBlank())
        val client = OkHttpClient.Builder().callTimeout(45, TimeUnit.SECONDS).build()
        listOfNotNull(info.video, info.audio).forEach { url ->
            client.newCall(Request.Builder().url(url).header("Range", "bytes=0-65535")
                .header("User-Agent", info.userAgent).header("Referer", info.referer).build()).execute().use {
                assertTrue("Media HTTP ${it.code}", it.isSuccessful)
                val bytes = ByteArray(32)
                assertTrue(it.body!!.byteStream().read(bytes) > 0)
                println("Media HTTP ${it.code}; type=${it.body!!.contentType()}")
            }
        }
    }
}
