package com.daxiaamu.dbdown
import android.app.NotificationManager
import android.content.ClipboardManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
@RunWith(AndroidJUnit4::class)
class HeadsUpAppearanceTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    @Test fun parsingResultAndFailureAreVisibleInAppWithoutNativePrompts() {
        val vm=ViewModelProvider(rule.activity)[MainViewModel::class.java]
        val clipboard=rule.activity.getSystemService(ClipboardManager::class.java)
        val old=clipboard.primaryClip
        val enabled=vm.clipboardEnabled
        val link=Links.detect("https://www.douyin.com/video/7690882615418703138")!!
        try {
            rule.runOnIdle { vm.setClipboard(false); clipboard.clearPrimaryClip(); vm.settings=false; vm.inputVisible=false }
            Thread.sleep(1000)
            rule.runOnIdle { vm.setClipboard(true); vm.clipboard.state=ClipboardPrompt.Resolving(link) }
            rule.onNodeWithTag("downloadHeadsUp").assertIsDisplayed()
            rule.onNodeWithText("正在解析链接，请稍候…").assertIsDisplayed()
            rule.onNode(hasText("下载") and hasAnyAncestor(hasTestTag("downloadHeadsUp"))).assertIsNotEnabled()
            val bounds=rule.onNodeWithTag("downloadHeadsUp").fetchSemanticsNode().boundsInRoot
            if(avoidsSystemIsland) assertTrue(bounds.top>=96*rule.activity.resources.displayMetrics.density)
            for(page in 0..2) {
                rule.runOnIdle { vm.settings=page==2; if(page<2) vm.tab=page }
                rule.onNodeWithTag("downloadHeadsUp").assertIsDisplayed()
            }
            rule.runOnIdle {
                vm.settings=false
                vm.clipboard.state=ClipboardPrompt.Ready(link,VideoInfo(link,link.key,"视频解析完成，可以下载","",referer="",userAgent=""))
            }
            rule.onNodeWithText("发现抖音视频").assertIsDisplayed()
            rule.onNode(hasText("下载") and hasAnyAncestor(hasTestTag("downloadHeadsUp"))).assertIsEnabled()
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(rule.activity.getExternalFilesDir(null),"headsup-restored.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            }
            bitmap.recycle()
            assertFalse(rule.activity.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id==9502 })
            rule.runOnIdle { vm.clipboard.state=ClipboardPrompt.Failed(link) }
            rule.onNodeWithText("重试").assertIsDisplayed()
            rule.onNodeWithContentDescription("忽略此提示").performClick()
            rule.onNodeWithTag("downloadHeadsUp").assertDoesNotExist()
        } finally {
            rule.runOnIdle {
                vm.setClipboard(false); vm.settings=false
                if(old!=null) clipboard.setPrimaryClip(old) else clipboard.clearPrimaryClip()
                vm.setClipboard(enabled)
            }
        }
    }
}
