package com.daxiaamu.dbdown

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AlbumIntegrationTest {
    @Test fun actualSharedNoteSavesPictureAndVideoWithMusic() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: MainViewModel
            var wasPaused = false
            scenario.onActivity { activity ->
                vm = ViewModelProvider(activity)[MainViewModel::class.java]
                check(vm.store.tasks.value.none { it.status.active }) { "Do not interrupt existing downloads" }
                wasPaused = vm.store.paused.value
                vm.store.resumeAll()
            }
            try {
                for(mode in AlbumMode.entries) {
                    var id = ""
                    scenario.onActivity {
                        val existing = vm.store.tasks.value.firstOrNull { it.key == "dy:7689856421856364794" && it.albumMode == mode }
                        if(existing != null) { id = existing.id; if(existing.status != TaskStatus.COMPLETED) vm.retry(id) }
                        else {
                            vm.openInput("https://v.douyin.com/QbQGcMP060w/"); vm.albumMode = mode
                            assertTrue(vm.error, vm.submit()); id = vm.store.tasks.value.first().id
                        }
                    }
                    val deadline = System.currentTimeMillis() + 180000
                    while(vm.store.get(id)?.status?.active == true && System.currentTimeMillis() < deadline) Thread.sleep(500)
                    val task = vm.store.get(id)!!
                    assertEquals(task.error, TaskStatus.COMPLETED, task.status)
                    val context = InstrumentationRegistry.getInstrumentation().targetContext
                    if(mode == AlbumMode.IMAGES) {
                        assertEquals("image/*", task.mimeType); assertEquals(1, task.outputUris.size)
                        context.contentResolver.openInputStream(Uri.parse(task.uri)).use {
                            val bitmap = android.graphics.BitmapFactory.decodeStream(it)
                            assertNotNull(bitmap); bitmap!!.recycle()
                        }
                    } else {
                        val extractor = MediaExtractor()
                        try {
                            extractor.setDataSource(context, Uri.parse(task.uri), null)
                            val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                            assertTrue(formats.any { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true })
                            assertTrue(formats.any { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true })
                        } finally { extractor.release() }
                    }
                }
            } finally { scenario.onActivity { if(wasPaused) vm.store.pauseAll() } }
        }
    }

    @Test fun mixedPortraitAndLandscapeImagesSpanCompleteMusic() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "album-fixture-${System.nanoTime()}").apply { mkdirs() }
        try {
            val images = listOf(240 to 400, 400 to 240).mapIndexed { i, (width, height) ->
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(if(i == 0) Color.RED else Color.BLUE)
                File(dir, "$i.png").also { file -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
            }
            val music = File(dir, "music.m4a")
            val session = com.arthenica.ffmpegkit.FFmpegKit.executeWithArguments(arrayOf(
                "-y", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
                "-t", "6", "-c:a", "aac", music.absolutePath))
            assertTrue(com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode))
            val output = File(dir, "album.mp4")
            AlbumExporter.export(context, images, music, output)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(output.absolutePath)
                assertEquals(2, extractor.trackCount)
                val format = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                    .first { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                assertEquals("video/avc", format.getString(MediaFormat.KEY_MIME))
                assertTrue(format.getLong(MediaFormat.KEY_DURATION) in 5_800_000L..6_200_000L)
            } finally { extractor.release() }
        } finally { dir.deleteRecursively() }
    }
}
