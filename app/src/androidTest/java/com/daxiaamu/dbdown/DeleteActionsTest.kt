package com.daxiaamu.dbdown

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeleteActionsTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val vm get() = ViewModelProvider(rule.activity)[MainViewModel::class.java]
    private val resolver get() = rule.activity.contentResolver
    private fun makePicture(): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "DBDown-test-${System.nanoTime()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/逗逼下载器")
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return uri
    }
    private fun readable(uri: Uri) = runCatching { resolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)

    @Test fun fileDeletionNeedsSecondConfirmationAndRecordOnlyPreservesFile() {
        val uris = listOf(makePicture(), makePicture(), makePicture())
        val ids = mutableListOf<String>()
        var originalPaused = false
        rule.runOnIdle {
            check(vm.store.tasks.value.none { it.status.active })
            originalPaused = vm.store.paused.value
            vm.store.pauseAll(); vm.settings = false; vm.tab = 1
            repeat(2) { index ->
                val task = vm.store.add(Links.detect("https://www.bilibili.com/video/BV1xx411c7mD?p=${7200+index}")!!)
                ids += task.id
                val files = if(index == 0) uris.take(2) else listOf(uris.last())
                vm.store.update(task.id) { it.copy(status = TaskStatus.COMPLETED, uri = files.first().toString(), outputUris = files.map(Uri::toString), mimeType = "image/*", title = "Delete test fixture") }
            }
        }
        try {
            rule.waitForIdle()
            rule.onNodeWithContentDescription("全部开始").assertIsDisplayed()
            rule.onNodeWithText("最多同时下载 3 个任务").assertDoesNotExist()
            // Open the actual clear-all entry, but never authorize deleting user's records.
            rule.onNodeWithTag("clearDownloads").performClick()
            rule.onNodeWithText("仅删除任务").assertExists()
            rule.onNodeWithText("取消").performClick()
            // Use the exact same bulk dialog with fixture IDs only.
            rule.runOnIdle { vm.requestDelete(listOf(ids.first()), all = true) }
            rule.onNodeWithText("删除任务和文件").performClick()
            rule.onNodeWithText("确认删除文件").assertIsDisplayed()
            assertTrue(uris.all(::readable))
            rule.onNodeWithText("取消").performClick()
            rule.runOnIdle { assertNotNull(vm.store.get(ids.first())); vm.requestDelete(listOf(ids.first()), withFiles = true) }
            rule.onNodeWithTag("confirmDeleteTasks").performClick()
            rule.waitUntil(10000) { !vm.deleting && vm.store.get(ids.first()) == null }
            assertFalse(readable(uris[0])); assertFalse(readable(uris[1]))
            rule.runOnIdle { vm.requestDelete(listOf(ids.last()), all = true) }
            rule.onNodeWithText("仅删除任务").performClick()
            rule.waitUntil(10000) { !vm.deleting && vm.store.get(ids.last()) == null }
            assertTrue(readable(uris.last()))
        } finally {
            rule.runOnIdle {
                vm.dismissDeletion()
                ids.forEach { id -> vm.store.update(id) { it.copy(status = TaskStatus.CANCELLED) }; vm.store.remove(id) }
                if(!originalPaused) vm.store.resumeAll()
            }
            uris.forEach { runCatching { resolver.delete(it, null, null) } }
        }
    }

    @Test fun deletionStopsPendingTasksBeforeRemovingAndKeepsFailedFileRecords() = runBlocking {
        val name = "delete-tests-${System.nanoTime()}"
        val store = DownloadStore(rule.activity, name)
        try {
            val pending = store.add(Links.detect("BV1xx411c7mD")!!)
            val failed = store.add(Links.detect("av170001")!!)
            store.update(failed.id) { it.copy(status = TaskStatus.COMPLETED, uri = "content://test/denied") }
            var stopped = false
            val result = deleteDownloadTasks(store, listOf(pending.id, failed.id), true, stop = { ids ->
                assertEquals(TaskStatus.CANCELLED, store.get(pending.id)?.status)
                assertEquals(2, ids.size); stopped = true
            }, deleteFile = { check(stopped); throw SecurityException("fixture") })
            assertEquals(1, result.removed); assertEquals(1, result.failed)
            assertNull(store.get(pending.id)); assertNotNull(store.get(failed.id))
            assertTrue(store.get(failed.id)!!.error.isNotBlank())
        } finally { rule.activity.deleteSharedPreferences(name) }
    }
}
