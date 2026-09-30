package com.daxiaamu.dbdown

import android.content.ClipData
import android.content.ClipboardManager
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Live device integration: clipboard is written only while our activity has input focus. */
@RunWith(AndroidJUnit4::class)
class ClipboardIntegrationTest {
    @Test fun clipboardFiltersVerifiesAndDeduplicatesAcrossRecreation() {
        val testContext = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = testContext.getSharedPreferences("settings", 0)
        prefs.edit().putStringSet("handled", prefs.getStringSet("handled", emptySet()).orEmpty() - "dy:7641264887980657961").commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var oldClip: ClipData? = null
            var vm: MainViewModel? = null
            var manager: ClipboardManager? = null
            var previousSetting = true
            scenario.onActivity { activity ->
                manager = activity.getSystemService(ClipboardManager::class.java)
                oldClip = manager!!.primaryClip
                vm = ViewModelProvider(activity)[MainViewModel::class.java]
                previousSetting = vm!!.clipboardEnabled
                vm!!.setClipboard(true)
                manager!!.setPrimaryClip(ClipData.newPlainText("test", "ordinary clipboard text"))
            }
            try {
                Thread.sleep(400)
                scenario.onActivity { assertNull(vm!!.clipboardPrompt) }
                scenario.onActivity {
                    manager!!.setPrimaryClip(ClipData.newPlainText("test", "https://www.douyin.com/user/12345678"))
                }
                Thread.sleep(400)
                scenario.onActivity { assertNull(vm!!.clipboardPrompt) }
                scenario.onActivity {
                    manager!!.setPrimaryClip(ClipData.newPlainText("test", "https://www.douyin.com/video/7641264887980657961"))
                }
                var found = false
                val deadline = System.currentTimeMillis() + 60000
                while(!found && System.currentTimeMillis() < deadline) {
                    Thread.sleep(250)
                    scenario.onActivity { found = vm!!.clipboardPrompt is ClipboardPrompt.Ready }
                }
                assertTrue("Valid Douyin clip must be recognized locally and show a prompt", found)
                val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                Thread.sleep(6000) // Let the OEM clipboard overlay expire before capturing our own banner.
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "clipboard-headsup.png").outputStream().use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
                scenario.onActivity {
                    assertEquals(Platform.DOUYIN, vm!!.clipboardPrompt!!.link.platform)
                    assertTrue((vm!!.clipboardPrompt as ClipboardPrompt.Ready).info.title.isNotBlank())
                    vm!!.dismissClipboard()
                }
                scenario.recreate()
                Thread.sleep(1000)
                scenario.onActivity { activity -> vm = ViewModelProvider(activity)[MainViewModel::class.java]; assertNull("Dismissed clip must not prompt again", vm!!.clipboardPrompt) }
                scenario.onActivity { vm!!.setClipboard(false) }
                scenario.recreate()
                scenario.onActivity { activity ->
                    vm = ViewModelProvider(activity)[MainViewModel::class.java]
                    assertFalse("Settings must survive recreation", vm!!.clipboardEnabled)
                }
            } finally {
                scenario.onActivity {
                    oldClip?.let { clip -> manager!!.setPrimaryClip(clip) } ?: manager!!.clearPrimaryClip()
                    vm!!.setClipboard(previousSetting)
                    vm!!.dismissClipboard()
                }
            }
        }
    }
}
