package com.daxiaamu.dbdown

import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DouyinQualityTest {
    @Test fun downloadedFixtureMatchesAndroidMetadata() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "quality-fixture.mp4")
        try {
            assertTrue(file.length() > 0)
            val during = partialVideoResolution(file)
            val final = MediaMetadataRetriever().use {
                it.setDataSource(file.absolutePath)
                resolutionLabel(it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt(),
                    it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt())
            }
            assertEquals("1920 × 1080", final)
            assertEquals(final, during)
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "Actual file: $during; Android metadata: $final\n") })
        } finally { file.delete() }
    }
    @Test fun suppliedWorkDownloadsAtVerified1080p() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val link = Links.detect("https://www.douyin.com/jingxuan?modal_id=7687764891221937427")!!
        val info = VideoResolver().resolve(link)
        assertEquals("",info.resolution)
        val file = File.createTempFile("quality-check-", ".mp4", instrumentation.targetContext.cacheDir)
        var during = ""
        try {
            downloadWithFallback(listOf(info.video) + info.videoFallbacks) { url ->
                ResumableTransfer(VideoResolver.client) {}.download(url,file,info.id,info.userAgent,info.referer) { bytes, _, _ ->
                    if(during.isEmpty() && bytes > 65536) during = partialVideoResolution(file)
                }
            }
            val final = MediaMetadataRetriever().use {
                it.setDataSource(file.absolutePath)
                resolutionLabel(it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt(),
                    it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt())
            }
            instrumentation.sendStatus(0,android.os.Bundle().apply {
                putString("stream","Downloaded ${file.length()} bytes; during=$during; final=$final\n")
            })
            assertEquals("1920 × 1080", final)
            assertEquals(final,during)
        } finally { file.delete(); File(file.path + ".resume").delete() }
    }
}
