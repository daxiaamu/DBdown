package com.daxiaamu.dbdown

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Opt-in network/device regression; never creates download records or public media. */
class Youtube4kMuxTest {
    @Test fun original4kStreamsSurviveMp4Merge() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("youtube4k") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "youtube-4k-${System.nanoTime()}").apply { check(mkdirs()) }
        try {
            val fixturePath = InstrumentationRegistry.getArguments().getString("youtube4kFixtures")
            if(fixturePath != null) {
                val fixture = File(fixturePath)
                val output = File(dir, "fixture.mp4")
                LosslessMuxer.merge(File(fixture,"video"), File(fixture,"audio"), output, File(fixture,"audio-codec").readText())
                verify(output, 0)
                return@runBlocking
            }
            val info = YoutubeResolver.resolve(Links.detect("b-Ag7meqZoU")!!) {}
            val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.MINUTES).build()
            fun download(url: String, name: String): File = File(dir, name).also { file ->
                client.newCall(Request.Builder().url(url).header("User-Agent", info.userAgent)
                    .header("Referer", info.referer).build()).execute().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                    file.outputStream().use { response.body!!.byteStream().copyTo(it) }
                }
            }
            val video = download(info.video, "video")
            val audio = download(requireNotNull(info.audio), "audio")
            val output = File(dir, "merged.mp4")
            LosslessMuxer.merge(video, audio, output, info.audioCodec)
            verify(output, 10_000_000L)
        } finally { dir.deleteRecursively() }
    }
    private fun verify(output: File, minimumDuration: Long) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(output.absolutePath)
            val tracks = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            val picture = tracks.first { it.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
            assertTrue(picture.toString(), minOf(picture.getInteger(MediaFormat.KEY_WIDTH), picture.getInteger(MediaFormat.KEY_HEIGHT)) >= 2160)
            assertTrue(tracks.any { it.getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") })
            assertTrue(picture.getLong(MediaFormat.KEY_DURATION) > minimumDuration)
            println("4K merged: ${output.length()} bytes; $tracks")
        } finally { extractor.release() }
    }
}
