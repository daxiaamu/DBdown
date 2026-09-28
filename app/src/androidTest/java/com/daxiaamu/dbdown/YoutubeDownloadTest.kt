package com.daxiaamu.dbdown

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class YoutubeDownloadTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun shortsDownloadsMergesAndSaves() = runBlocking<Unit> {
        val vm = ViewModelProvider(rule.activity)[MainViewModel::class.java]
        val store = vm.store
        assertFalse("Do not interfere with a paused user queue",store.paused.value)
        val link = Links.detect("https://www.youtube.com/shorts/-9OM3w3TWUs")!!
        val task = store.add(link) ?: error("Sample already exists; keep the user's record untouched")
        try {
            rule.runOnIdle { vm.settings = false; vm.tab = 1; DownloadService.start(rule.activity) }
            val completed = withTimeout(240_000) {
                while(store.get(task.id)?.status?.pending == true) delay(500)
                store.get(task.id)!!
            }
            assertEquals(completed.error, TaskStatus.COMPLETED, completed.status)
            assertEquals("1080 × 1920",completed.resolution)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(rule.activity,Uri.parse(completed.uri),null)
                val mime = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
                assertTrue(mime.any { it.startsWith("video/") })
                assertTrue(mime.any { it.startsWith("audio/") })
            } finally { extractor.release() }
        } finally {
            DownloadService.cancel(rule.activity,task.id)
            DownloadService.awaitCancellation(listOf(task.id))
            store.get(task.id)?.uri?.takeIf { it.isNotBlank() }?.let { rule.activity.contentResolver.delete(Uri.parse(it),null,null) }
            store.update(task.id) { it.copy(status=TaskStatus.CANCELLED) }
            store.remove(task.id)
        }
    }
}
