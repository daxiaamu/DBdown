package com.daxiaamu.dbdown

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadIntegrationTest {
    @Test fun bilibiliDownloadsMuxesAndPublishesPlayableVideoAndAudio() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var vm: MainViewModel? = null
            var id = ""
            scenario.onActivity { activity ->
                vm = ViewModelProvider(activity)[MainViewModel::class.java]
                vm!!.tab = 1
                val prior = vm!!.store.tasks.value.firstOrNull { it.key == "BV1xx411c7mD:p1" }
                if(prior != null) {
                    id = prior.id
                    if(prior.status != TaskStatus.COMPLETED) vm!!.retry(id)
                } else {
                    vm!!.openInput("BV1xx411c7mD"); assertTrue(vm!!.submit())
                    id = vm!!.store.tasks.value.first().id
                }
            }
            var task: DownloadTask? = null
            val deadline = System.currentTimeMillis() + 180000
            while(System.currentTimeMillis() < deadline) {
                Thread.sleep(500)
                task = vm!!.store.get(id)
                if(task?.status?.active != true) break
            }
            assertEquals(task?.error, TaskStatus.COMPLETED, task?.status)
            val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, Uri.parse(task!!.uri), null)
                val formats = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                assertTrue(formats.any { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true })
                assertTrue(formats.any { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true })
                formats.forEach { assertTrue(it.getLong(MediaFormat.KEY_DURATION) > 0) }
                val durations = formats.map { it.getLong(MediaFormat.KEY_DURATION) }
                assertTrue("Audio/video durations must remain aligned", durations.max()-durations.min() < 5_000_000L)
            } finally { extractor.release() }
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            java.io.File(context.getExternalFilesDir(null), "downloads-completed.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
        }
    }
    @Test fun douyinDownloadsAndPublishesPlayableVideo() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var vm: MainViewModel? = null
            var id = ""
            scenario.onActivity { activity ->
                vm = ViewModelProvider(activity)[MainViewModel::class.java]
                vm!!.tab = 1
                val prior = vm!!.store.tasks.value.firstOrNull { it.key == "dy:7679018882060990022" }
                if(prior != null) {
                    id = prior.id
                    if(prior.status != TaskStatus.COMPLETED) vm!!.retry(id)
                } else {
                    vm!!.openInput("https://www.douyin.com/video/7679018882060990022"); assertTrue(vm!!.submit())
                    id = vm!!.store.tasks.value.first().id
                }
            }
            var task: DownloadTask? = null
            val deadline = System.currentTimeMillis() + 90000
            while(System.currentTimeMillis() < deadline) {
                Thread.sleep(500)
                task = vm!!.store.get(id)
                if(task?.status?.active != true) break
            }
            assertEquals(task?.error, TaskStatus.COMPLETED, task?.status)
            val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, Uri.parse(task!!.uri), null)
                assertTrue((0 until extractor.trackCount).any {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                })
                assertTrue(task!!.bytes > 0)
            } finally { extractor.release() }
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            java.io.File(context.getExternalFilesDir(null), "downloads-both-completed.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
        }
    }
}