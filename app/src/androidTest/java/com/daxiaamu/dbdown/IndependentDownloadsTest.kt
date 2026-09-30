package com.daxiaamu.dbdown

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
class IndependentDownloadsTest {
    private fun withStore(block: (DownloadStore, String) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "independent-downloads-${System.nanoTime()}"
        try { block(DownloadStore(context,name),name) }
        finally { context.deleteSharedPreferences(name) }
    }
    private val link = Links.detect("https://www.douyin.com/video/7690882615418703138")!!
    private fun info() = VideoInfo(link,link.key,"Test","https://example.com/video.mp4",
        referer=link.url,userAgent="test")

    @Test fun sameLinkCreatesIndependentTasksEvenWhenAlreadyCompleted() = withStore { store,name ->
        val first = store.add(link)
        assertTrue(store.markResolved(first.id,info()))
        store.update(first.id) { it.copy(status=TaskStatus.COMPLETED,uri="content://test/first",bytes=100,total=100) }
        val second = store.add(link)
        assertNotEquals(first.id,second.id)
        assertTrue(store.markResolved(second.id,info()))
        assertEquals(TaskStatus.COMPLETED,store.get(first.id)!!.status)
        assertEquals("content://test/first",store.get(first.id)!!.uri)
        val restored = DownloadStore(InstrumentationRegistry.getInstrumentation().targetContext,name)
        assertEquals(listOf(second.id,first.id),restored.tasks.value.map { it.id })
        assertEquals(2,restored.tasks.value.count { it.key==link.key })
    }
    @Test fun concurrentShortAndDirectAliasesCanResolveAndRetryIndependently() = withStore { store,_ ->
        val first = store.add(Links.detect("https://v.douyin.com/aZEcdnIkHpI/")!!)
        val second = store.add(link)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = listOf(first,second).map { task -> executor.submit<Boolean> { store.markResolved(task.id,info()) } }
            assertTrue(results.all { it.get() })
        } finally { executor.shutdownNow() }
        store.update(first.id) { it.copy(status=TaskStatus.FAILED,error="test") }
        assertTrue(store.retry(first.id))
        assertEquals(TaskStatus.DOWNLOADING,store.get(second.id)!!.status)
        store.pauseAll()
        assertFalse(store.markResolved(first.id,info()))
        val third = store.add(link)
        assertEquals(TaskStatus.PAUSED,third.status)
        store.resumeAll()
        assertTrue(store.tasks.value.all { it.status==TaskStatus.QUEUED })
    }
    @Test fun deletingOneDuplicateKeepsTheOthersCacheAndRecord() = withStore { store,_ ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tasks = listOf(store.add(link),store.add(link))
        val directories = tasks.map { File(context.cacheDir,"download-${it.id}").apply { mkdirs() } }
        try {
            directories.forEach { File(it,"video.mp4").writeText("partial resource") }
            store.update(tasks[0].id) { it.copy(status=TaskStatus.CANCELLED) }
            store.remove(tasks[0].id)
            assertNull(store.get(tasks[0].id))
            assertFalse(directories[0].exists())
            assertNotNull(store.get(tasks[1].id))
            assertEquals("partial resource",File(directories[1],"video.mp4").readText())
        } finally { directories.forEach { it.deleteRecursively() } }
    }
}
