package com.daxiaamu.dbdown

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HeadsUpAppearanceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun glassPromptDrawsAndDismisses() {
        val vm = ViewModelProvider(rule.activity)[MainViewModel::class.java]
        val enabled = vm.clipboardEnabled
        try {
            rule.runOnIdle { vm.setClipboard(false); vm.settings = false; vm.inputVisible = false; vm.tab = 0 }
            rule.waitForIdle()
            rule.runOnIdle {
                vm.setClipboard(true)
                val link = Links.detect("https://www.douyin.com/video/7678889454471351592")!!
                vm.clipboardSuggestion = VideoInfo(link, link.key,
                    "一个视频看懂 World Action Model（世界动作模型），预测未来的变化", "", referer = "", userAgent = "")
            }
            rule.onNodeWithTag("downloadHeadsUp").assertIsDisplayed()
            rule.waitForIdle()
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.getExternalFilesDir(null), "heads-up-fixed.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
            rule.onNodeWithContentDescription("忽略此视频").performClick()
            rule.onNodeWithTag("downloadHeadsUp").assertDoesNotExist()
        } finally { rule.runOnIdle { vm.clipboardSuggestion = null; vm.setClipboard(enabled) } }
    }
}
