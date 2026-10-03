package com.daxiaamu.dbdown

import android.media.MediaMetadataRetriever
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Opt-in live checks; downloaded fixtures stay in private cache and are always removed. */
class WeiboOnlineTest {
    @Test fun signedInWeiboSessionIsRecognized() {
        WebAccounts.refresh(true)
        val deadline = System.currentTimeMillis() + 65_000
        while(System.currentTimeMillis() < deadline && WebAccounts.statuses.value[Platform.WEIBO] != AccountStatus.VALID)
            Thread.sleep(500)
        assertEquals(AccountStatus.VALID, WebAccounts.statuses.value[Platform.WEIBO])
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putString("stream", "Weibo account verified VALID through official API\n")
        })
    }
    @Test fun providedVideoPageDownloadsWithAudio() = verifyDownload("https://video.weibo.com/show?fid=1034:5349222019694630")
    @Test fun providedTvPageDownloadsWithAudio() = verifyDownload("https://weibo.com/tv/show/1034:5349506317746213?from=old_pc_videoshow")

    private fun verifyDownload(url: String) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun report(text: String) = instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", text + "\n") })
        val info = VideoResolver().resolve(Links.detect(url)!!)
        assertEquals(Platform.WEIBO, info.source.platform)
        report("Resolved ${info.source.key}: ${info.title}; ${info.resolution}; ${info.fps} fps")
        report("Available: ${info.specifications?.videos?.joinToString { it.label + ": " + it.detail }}")
        val file = File.createTempFile("weibo-provided-", ".mp4", instrumentation.targetContext.cacheDir)
        try {
            VideoResolver.client.newCall(Request.Builder().url(info.video).header("Referer",info.referer).header("User-Agent",info.userAgent).build()).execute().use { response ->
                assertTrue("Media HTTP ${response.code}", response.isSuccessful)
                val body = response.body!!
                body.byteStream().use { input -> file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while(true) {
                        val count = input.read(buffer)
                        if(count < 0) break
                        output.write(buffer,0,count)
                        check(file.length() <= 512L * 1024 * 1024) { "Fixture exceeds 512 MiB test budget" }
                    }
                } }
                if(body.contentLength() >= 0) assertEquals(body.contentLength(),file.length())
            }
            MediaMetadataRetriever().use { media ->
                media.setDataSource(file.absolutePath)
                val duration = media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                val width = media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val height = media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                val audio = media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
                report("Downloaded ${file.length()} bytes; ${width}x${height}; duration ${duration} ms; audio=$audio")
                assertTrue(duration > 0)
                assertEquals("yes", audio)
                if(info.resolution.isNotBlank()) assertEquals(info.resolution, "$width × $height")
            }
        } finally { file.delete() }
    }

    @Test fun publicPostDownloadsCompleteVideoWithAudio()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val info=VideoResolver().resolve(Links.detect("https://m.weibo.cn/detail/4189191225395228")!!)
        assertEquals(Platform.WEIBO,info.source.platform)
        assertTrue(info.specifications!!.videos.size>=2)
        val file=File.createTempFile("weibo-fixture-",".mp4",instrumentation.targetContext.cacheDir)
        try {
            VideoResolver.client.newCall(Request.Builder().url(info.video).header("Referer",info.referer).header("User-Agent",info.userAgent).build()).execute().use {
                assertTrue("Media HTTP ${it.code}",it.isSuccessful)
                val data=it.body!!.byteStream().readNBytes(8*1024*1024+1)
                assertTrue(data.size<8*1024*1024); file.writeBytes(data)
            }
            MediaMetadataRetriever().use {
                it.setDataSource(file.absolutePath)
                assertTrue(it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()>50_000)
                assertEquals("yes",it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                assertEquals("480",it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
                instrumentation.sendStatus(0,android.os.Bundle().apply { putString("stream","Weibo downloaded complete 53-second video with audio; ${file.length()} bytes\n") })
            }
        } finally { file.delete() }
    }
    @Test fun shortLinkAndVideoPageResolveToSamePost()=runBlocking {
        for(url in listOf("https://t.cn/RHbmjzW","https://weibo.com/tv/show/1034:633c288cc043d0ca7808030f1157da64")) {
            val info=VideoResolver().resolve(Links.detect(url)!!)
            assertEquals("wb:4189191225395228",info.source.key)
        }
    }
}
