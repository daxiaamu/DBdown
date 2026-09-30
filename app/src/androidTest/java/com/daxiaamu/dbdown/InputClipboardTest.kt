package com.daxiaamu.dbdown

import android.content.ClipData
import android.content.ClipboardManager
import android.os.PersistableBundle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import android.app.NotificationManager
import org.junit.Assume.assumeTrue

@RunWith(AndroidJUnit4::class)
class InputClipboardTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val vm get() = ViewModelProvider(rule.activity)[MainViewModel::class.java]
    private val clipboard get() = rule.activity.getSystemService(ClipboardManager::class.java)
    private fun notification() = rule.activity.getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull { it.id == 9502 }?.notification
    @Test fun focusSuggestsFillWithoutOverwritingAndClearRemovesEverything() {
        val original = clipboard.primaryClip
        var enabled = true
        val text = "【见你所见】 https://b23.tv/2vBN1dt"
        try {
            rule.runOnIdle {
                enabled = vm.clipboardEnabled; vm.setClipboard(true)
                vm.settings = false; vm.tab = 0
                vm.openInput("尚未提交的输入")
                clipboard.setPrimaryClip(ClipData.newPlainText("test", text))
            }
            rule.onNodeWithTag("fillClipboardLink").assertIsDisplayed().assertTextEquals("填入")
            rule.runOnIdle { assertEquals("尚未提交的输入", vm.input) }
            rule.onNodeWithTag("inputClipboardHeadsUp").assertDoesNotExist()
            assertNull(notification())
            rule.onNodeWithTag("fillClipboardLink").performClick()
            rule.waitUntil(5000) { vm.input == text }
            rule.onNodeWithTag("downloadLinkInput").assertTextEquals(text)
            rule.onNodeWithContentDescription("清空输入").assertIsDisplayed().performClick()
            rule.runOnIdle { assertEquals("", vm.input); assertNull(vm.error) }
            rule.onNodeWithTag("clearLinkInput").assertDoesNotExist()
            rule.onNodeWithTag("inputClipboardHeadsUp").assertDoesNotExist()
        } finally {
            rule.runOnIdle {
                vm.inputVisible = false; vm.setClipboard(false)
                if(original != null) clipboard.setPrimaryClip(original) else clipboard.clearPrimaryClip()
                vm.setClipboard(enabled)
            }
        }
    }
    @Test fun unrelatedSensitiveAndDisabledClipboardNeverSuggestFill() {
        val original = clipboard.primaryClip
        var enabled = true
        try {
            rule.runOnIdle { enabled = vm.clipboardEnabled }
            val sensitive = ClipData.newPlainText("sensitive test", "https://b23.tv/2vBN1dt").apply {
                description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
            }
            listOf(
                ClipData.newPlainText("test", "普通文字"),
                ClipData.newPlainText("test", "https://www.douyin.com.evil.com/video/7685937709583519022"),
                sensitive
            ).forEach { clip ->
                rule.runOnIdle { vm.setClipboard(true); vm.openInput(); clipboard.setPrimaryClip(clip) }
                rule.waitForIdle()
                rule.onNodeWithTag("downloadLinkInput").assertIsFocused()
                assertNull(notification())
                rule.runOnIdle { vm.inputVisible = false }
                rule.waitForIdle()
            }
            rule.runOnIdle { vm.setClipboard(false); vm.openInput(); clipboard.setPrimaryClip(ClipData.newPlainText("test", "https://v.douyin.com/QbQGcMP060w/")) }
            rule.waitForIdle()
            rule.onNodeWithTag("inputClipboardHeadsUp").assertDoesNotExist()
        } finally {
            rule.runOnIdle {
                vm.inputVisible = false; vm.setClipboard(false)
                if(original != null) clipboard.setPrimaryClip(original) else clipboard.clearPrimaryClip()
                vm.setClipboard(enabled)
            }
        }
    }
    @Test fun douyinActionsStayVisibleAboveKeyboard() {
        var enabled = true
        try {
            rule.runOnIdle { enabled = vm.clipboardEnabled; vm.setClipboard(false); vm.openInput("https://v.douyin.com/QbQGcMP060w/") }
            rule.onNode(hasText("下载") and hasClickAction() and hasAnyAncestor(isDialog())).assertIsDisplayed()
            rule.onNodeWithContentDescription("清空输入").assertIsDisplayed()
        } finally { rule.runOnIdle { vm.inputVisible = false; vm.setClipboard(enabled) } }
    }

}
