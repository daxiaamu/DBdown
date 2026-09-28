package com.daxiaamu.dbdown

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ClipboardLivePromptTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val vm get() = ViewModelProvider(rule.activity)[MainViewModel::class.java]
    private val manager get() = rule.activity.getSystemService(NotificationManager::class.java)
    private fun notification(): Notification? = manager.activeNotifications.firstOrNull { it.id == ClipboardLivePrompt.NOTIFICATION_ID }?.notification
    private fun waitPrompt(): Notification {
        rule.waitUntil(10000) { notification()?.actions?.isNotEmpty() == true }
        return notification()!!
    }
    private fun status(text: String) { InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("stream", "$text\n") }) }
    private fun screenshot(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun nativeFillPreservesInputUntilClickAndConsumesActionOnce() {
        assumeTrue(ClipboardLivePrompt.allowed(rule.activity))
        val clipboard = rule.activity.getSystemService(ClipboardManager::class.java)
        val original = clipboard.primaryClip
        var enabled = true
        val text = "【见你所见】 https://b23.tv/2vBN1dt"
        try {
            rule.runOnIdle {
                enabled = vm.clipboardEnabled; vm.setClipboard(true); vm.settings = false
                vm.openInput("保留草稿")
                clipboard.setPrimaryClip(ClipData.newPlainText("test", text))
            }
            val prompt = waitPrompt()
            status("prompt ready")
            assertTrue(prompt.flags and Notification.FLAG_PROMOTED_ONGOING != 0)
            rule.runOnIdle { assertEquals("保留草稿", vm.input) }
            rule.waitForIdle()
            screenshot("native-fill-foreground.png")
            val model = vm
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
            Thread.sleep(1000)
            status("sending fill")
            prompt.actions.first { it.title.toString() == "填入" }.actionIntent.send()
            status("fill sent")
            rule.waitUntil(10000) { model.input == text }
            rule.waitForIdle()
            assertNull(notification())
            status("fill state received")
            rule.onNodeWithTag("downloadLinkInput").assertTextEquals(text)
            rule.onNodeWithContentDescription("清空输入").performClick()
            rule.runOnIdle {
                ClipboardLivePrompt.handle(Intent(ClipboardLivePrompt.ACTION, Uri.parse("dbdown-prompt://invalid/0")))
                assertEquals("", vm.input)
            }
            try { prompt.actions[0].actionIntent.send(); fail("Consumed action must be cancelled") }
            catch(_: PendingIntent.CanceledException) { }
        } finally {
            rule.runOnIdle {
                vm.inputVisible = false; vm.setClipboard(false)
                if(original != null) clipboard.setPrimaryClip(original) else clipboard.clearPrimaryClip()
                vm.setClipboard(enabled)
            }
        }
    }

    @Test fun nativeAlbumActionsQueueChosenModeAndCancelWhenPromptIsClosed() {
        assumeTrue(ClipboardLivePrompt.allowed(rule.activity))
        val clipboard = rule.activity.getSystemService(ClipboardManager::class.java)
        val original = clipboard.primaryClip
        var enabled = true
        var wasPaused = false
        var fixtureId: String? = null
        val link = Links.detect("https://www.douyin.com/note/7689856421856364997")!!
        try {
            rule.runOnIdle {
                check(vm.store.tasks.value.none { it.status.pending }) { "Do not interrupt real downloads" }
                enabled = vm.clipboardEnabled; wasPaused = vm.store.paused.value
                vm.setClipboard(false); clipboard.clearPrimaryClip()
                vm.openInput(); vm.inputVisible = false; vm.settings = false
                vm.store.pauseAll()
            }
            // Drain the real clipboard/window callbacks before injecting the resolved fixture.
            rule.waitForIdle()
            Thread.sleep(500)
            rule.runOnIdle {
                vm.setClipboard(true)
                vm.clipboardSuggestion = VideoInfo(link, link.key, "流体云图集测试", "", referer = "", userAgent = "", images = listOf("fixture"))
            }
            val prompt = waitPrompt()
            assertEquals(listOf("保存图片", "合成视频", "忽略"), prompt.actions.map { it.title.toString() })
            rule.waitForIdle()
            screenshot("native-download-foreground.png")
            prompt.actions.first { it.title.toString() == "合成视频" }.actionIntent.send()
            rule.waitUntil(10000) { vm.store.tasks.value.any { it.key == link.key } }
            rule.runOnIdle {
                val task = vm.store.tasks.value.first { it.key == link.key }; fixtureId = task.id
                assertEquals(AlbumMode.VIDEO, task.albumMode); assertEquals(TaskStatus.PAUSED, task.status)
                assertNull(vm.clipboardSuggestion)
            }
            rule.waitUntil(5000) { notification() == null }
            rule.runOnIdle {
                vm.clipboardSuggestion = VideoInfo(link, link.key, "取消提示测试", "", referer = "", userAgent = "")
            }
            val dismissed = waitPrompt()
            dismissed.actions.first { it.title.toString() == "忽略" }.actionIntent.send()
            rule.waitUntil(5000) { notification() == null && vm.clipboardSuggestion == null }
            rule.runOnIdle {
                vm.clipboardSuggestion = VideoInfo(link, link.key, "打开输入后旧操作失效", "", referer = "", userAgent = "")
            }
            val stale = waitPrompt()
            rule.runOnIdle { vm.openInput() }
            rule.waitUntil(5000) { notification() == null }
            try { stale.actions[0].actionIntent.send(); fail("Closed prompt must cancel old action") }
            catch(_: PendingIntent.CanceledException) { }
        } finally {
            rule.runOnIdle {
                vm.inputVisible = false; vm.setClipboard(false)
                fixtureId?.let { id -> vm.store.update(id) { it.copy(status = TaskStatus.CANCELLED) }; vm.store.remove(id) }
                if(!wasPaused) vm.store.resumeAll()
                if(original != null) clipboard.setPrimaryClip(original) else clipboard.clearPrimaryClip()
                vm.setClipboard(enabled)
            }
        }
    }
}
