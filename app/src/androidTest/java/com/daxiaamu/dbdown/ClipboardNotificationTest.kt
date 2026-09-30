package com.daxiaamu.dbdown

import android.app.Notification
import android.app.NotificationManager
import android.content.ClipboardManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardNotificationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun resolvingReadyAndFailedUseOnlySystemNotifications() {
        assumeTrue(ClipboardLivePrompt.allowed(rule.activity))
        val vm = ViewModelProvider(rule.activity)[MainViewModel::class.java]
        val manager = rule.activity.getSystemService(NotificationManager::class.java)
        fun notification() = manager.activeNotifications.firstOrNull { it.id == ClipboardLivePrompt.NOTIFICATION_ID }?.notification
        fun waitText(text: String) {
            rule.waitUntil(5000) { notification()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() == text }
        }
        val clipboard = rule.activity.getSystemService(ClipboardManager::class.java)
        val oldClip = clipboard.primaryClip
        val enabled = vm.clipboardEnabled
        val link = Links.detect("https://www.douyin.com/video/7690882615418703138")!!
        try {
            rule.runOnIdle {
                vm.setClipboard(false); clipboard.clearPrimaryClip()
                vm.inputVisible = false; vm.settings = false
            }
            Thread.sleep(1000)
            rule.runOnIdle { vm.setClipboard(true); vm.clipboard.state = ClipboardPrompt.Resolving(link) }
            waitText("发现可下载内容，正在解析…")
            rule.onNodeWithTag("downloadHeadsUp").assertDoesNotExist()
            assertEquals(listOf("忽略"), notification()!!.actions.map { it.title.toString() })
            Thread.sleep(1200) // Ordinary notifications must survive the former promotion polling timeout.
            assertNotNull(notification())
            rule.runOnIdle { vm.clipboard.state = ClipboardPrompt.Ready(link,
                VideoInfo(link,link.key,"解析完成测试","",referer="",userAgent="")) }
            waitText("解析完成测试")
            assertEquals(listOf("下载", "忽略"), notification()!!.actions.map { it.title.toString() })
            rule.runOnIdle { vm.clipboard.state = ClipboardPrompt.Failed(link) }
            waitText("解析失败，请检查网络后重试。")
            assertEquals(listOf("重试", "忽略"), notification()!!.actions.map { it.title.toString() })
            notification()!!.actions.first { it.title.toString() == "忽略" }.actionIntent.send()
            rule.waitUntil(5000) { notification() == null && vm.clipboardPrompt == null }
        } finally {
            rule.runOnIdle {
                vm.setClipboard(false)
                if(oldClip != null) clipboard.setPrimaryClip(oldClip) else clipboard.clearPrimaryClip()
                vm.setClipboard(enabled)
            }
        }
    }
}
