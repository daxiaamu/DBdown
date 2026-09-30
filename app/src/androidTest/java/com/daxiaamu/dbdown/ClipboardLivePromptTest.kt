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

    @Test fun openingInputInvalidatesTheOldGlobalPrompt() {
        assumeTrue(ClipboardLivePrompt.allowed(rule.activity))
        val clipboard=rule.activity.getSystemService(ClipboardManager::class.java)
        val original=clipboard.primaryClip
        val enabled=vm.clipboardEnabled
        val link=Links.detect("https://www.douyin.com/video/7689856421856364997")!!
        try {
            rule.runOnIdle { vm.setClipboard(false); clipboard.clearPrimaryClip(); vm.inputVisible=false }
            Thread.sleep(1000)
            rule.runOnIdle {
                vm.setClipboard(true)
                vm.clipboard.state=ClipboardPrompt.Ready(link,VideoInfo(link,link.key,"测试","",referer="",userAgent=""))
            }
            val prompt=waitPrompt()
            assertEquals(listOf("下载","忽略"),prompt.actions.map { it.title.toString() })
            rule.runOnIdle { vm.openInput() }
            rule.waitUntil(5000) { notification()==null }
            try { prompt.actions[0].actionIntent.send(); fail("Closed prompt must cancel old action") }
            catch(_: PendingIntent.CanceledException) {}
        } finally {
            rule.runOnIdle {
                vm.inputVisible=false; vm.setClipboard(false)
                if(original!=null) clipboard.setPrimaryClip(original) else clipboard.clearPrimaryClip()
                vm.setClipboard(enabled)
            }
        }
    }
}
