package com.daxiaamu.dbdown

import android.content.ClipData
import android.content.ClipboardManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class ForegroundClipboardObserverTest {
    @Test fun resumesOnEveryMainPageAndStopsWhilePausedOrDisabled() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val seen = CopyOnWriteArrayList<String?>()
            var enabled = true
            lateinit var observer: ForegroundClipboardObserver
            lateinit var clipboard: ClipboardManager
            var oldClip: ClipData? = null
            scenario.onActivity { activity ->
                clipboard = activity.getSystemService(ClipboardManager::class.java)
                oldClip = clipboard.primaryClip
                observer = ForegroundClipboardObserver(activity, { enabled }) { seen.add(it) }
                activity.lifecycle.addObserver(observer)
                clipboard.setPrimaryClip(ClipData.newPlainText("test", "foreground clipboard test"))
            }
            try {
                for(page in 0..2) {
                    scenario.onActivity { activity ->
                        val vm = ViewModelProvider(activity)[MainViewModel::class.java]
                        vm.tab = if(page == 1) 1 else 0
                        vm.settings = page == 2
                    }
                    scenario.moveToState(Lifecycle.State.CREATED)
                    seen.clear()
                    Thread.sleep(900)
                    assertTrue("No clipboard reads while paused", seen.isEmpty())
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    Thread.sleep(1200)
                    assertTrue("Page $page must read clipboard on resume", seen.contains("foreground clipboard test"))
                }
                scenario.moveToState(Lifecycle.State.CREATED)
                enabled = false
                seen.clear()
                scenario.moveToState(Lifecycle.State.RESUMED)
                Thread.sleep(1200)
                assertTrue("Disabled observer must not read clipboard", seen.isEmpty())
            } finally {
                scenario.onActivity { activity ->
                    observer.onPause(activity)
                    activity.lifecycle.removeObserver(observer)
                    if(oldClip != null) clipboard.setPrimaryClip(oldClip!!) else clipboard.clearPrimaryClip()
                }
            }
        }
    }
}
