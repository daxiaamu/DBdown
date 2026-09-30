package com.daxiaamu.dbdown

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueueControlsTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val vm get() = ViewModelProvider(rule.activity)[MainViewModel::class.java]

    @Test fun pausedQueueAndParallelismPersistWithIndependentDuplicateTasks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "queue-test-${System.nanoTime()}"
        try {
            val store = DownloadStore(context, name)
            assertEquals(3, store.parallelism.value)
            val link = Links.detect("BV1xx411c7mD")!!
            val task = store.add(link)
            store.update(task.id) { it.copy(status = TaskStatus.DOWNLOADING, bytes = 1234, total = 5000, speed = 600) }
            store.setParallelism(5); store.pauseAll()
            assertEquals(TaskStatus.PAUSED, store.get(task.id)!!.status)
            assertEquals(0L, store.get(task.id)!!.speed)
            assertEquals(1234L, store.get(task.id)!!.bytes)
            assertNotEquals(task.id, store.add(link).id)
            val added = store.add(Links.detect("https://www.douyin.com/video/7679018882060990022")!!)
            assertEquals(TaskStatus.PAUSED, added.status)
            val restored = DownloadStore(context, name)
            assertTrue(restored.paused.value); assertEquals(5, restored.parallelism.value)
            assertTrue(restored.tasks.value.all { it.status == TaskStatus.PAUSED })
            restored.resumeAll()
            assertFalse(restored.paused.value)
            assertTrue(restored.tasks.value.all { it.status == TaskStatus.QUEUED })
            restored.setParallelism(100); assertEquals(6, restored.parallelism.value)
            restored.setParallelism(0); assertEquals(1, restored.parallelism.value)
        } finally { context.deleteSharedPreferences(name) }
    }

    @Test fun buttonsControlRealServiceAndSettingsCanSelectConcurrency() {
        var originalLimit = 3
        var originallyPaused = false
        var id = ""
        val settings = rule.activity.getSharedPreferences("settings", 0)
        val asked = settings.getBoolean("notificationAsked", false)
        settings.edit().putBoolean("notificationAsked", true).apply()
        rule.runOnIdle {
            originalLimit = vm.store.parallelism.value
            originallyPaused = vm.store.paused.value
            check(vm.store.tasks.value.none { it.status.active }) { "Do not interrupt user downloads in this UI test" }
            vm.tab = 1
            // Queue a unique record without initiating network traffic.
            val task = vm.store.add(Links.detect("https://www.bilibili.com/video/BV1xx411c7mD?p=9876")!!)
            id = task.id
        }
        try {
            rule.onNodeWithTag("queueControl").performClick()
            rule.waitUntil(10000) { vm.store.paused.value }
            rule.onNodeWithContentDescription("全部开始").assertExists()
            rule.runOnIdle { assertEquals(TaskStatus.PAUSED, vm.store.get(id)!!.status) }
            rule.activityRule.scenario.recreate()
            rule.waitForIdle()
            rule.onNodeWithContentDescription("全部开始").assertExists()
            // Cancel only the fixture before resuming, keeping this test independent of platform APIs.
            rule.runOnIdle { vm.cancel(id) }
            rule.waitUntil(10000) { vm.store.get(id)?.status == TaskStatus.CANCELLED }
            rule.onNodeWithTag("queueControl").assertDoesNotExist()
            rule.runOnIdle { vm.resumeDownloads() }
            rule.waitUntil(10000) { !vm.store.paused.value }
            rule.waitForIdle()
            rule.onNodeWithContentDescription("全部暂停").assertDoesNotExist()
            rule.runOnIdle { vm.settings = true }
            rule.onNodeWithTag("parallelismSetting").performClick()
            rule.onNodeWithText("2 个任务").performClick()
            rule.runOnIdle { assertEquals(2, vm.store.parallelism.value); vm.settings = false }
            rule.onNodeWithText("最多同时下载 2 个任务").assertDoesNotExist()
        } finally {
            settings.edit().putBoolean("notificationAsked", asked).apply()
            rule.runOnIdle {
                vm.store.update(id) { it.copy(status = TaskStatus.CANCELLED) }; vm.store.remove(id)
                vm.store.setParallelism(originalLimit)
                if(originallyPaused) vm.store.pauseAll() else vm.store.resumeAll()
            }
        }
    }
    @Test fun newTaskClearsCompletedFilterAndReturnsToTop() {
        val ids = mutableListOf<String>()
        var wasPaused = false
        rule.runOnIdle {
            check(vm.store.tasks.value.none { it.status.active })
            wasPaused = vm.store.paused.value
            vm.store.pauseAll()
            repeat(16) { index ->
                val task = vm.store.add(Links.detect("https://www.bilibili.com/video/BV1xx411c7mD?p=${8000+index}")!!)
                ids += task.id
                vm.store.update(task.id) { it.copy(status = TaskStatus.COMPLETED, title = "scroll fixture $index") }
            }
            vm.settings = false; vm.tab = 1
        }
        try {
            rule.waitForIdle()
            rule.onNode(hasText("已完成") and hasClickAction()).performClick()
            rule.onNodeWithTag("downloadList").performScrollToIndex(15)
            var newId = ""
            rule.runOnIdle {
                vm.openInput("https://www.douyin.com/note/7689856421856364799")
                assertTrue(vm.submit())
                newId = vm.store.tasks.value.first().id; ids += newId
            }
            rule.waitForIdle()
            rule.onNodeWithTag("download-$newId").assertIsDisplayed()
            rule.onNodeWithText("全部").assertIsSelected()
        } finally {
            rule.runOnIdle {
                ids.forEach { id -> vm.store.update(id) { it.copy(status = TaskStatus.CANCELLED) }; vm.store.remove(id) }
                if(!wasPaused) vm.store.resumeAll()
            }
        }
    }

}
