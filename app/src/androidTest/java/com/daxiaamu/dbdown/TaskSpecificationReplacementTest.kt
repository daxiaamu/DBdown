package com.daxiaamu.dbdown

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TaskSpecificationReplacementTest {
    private val context get() = ApplicationProvider.getApplicationContext<DownloaderApp>()
    private val link=Links.detect("BV1xx411c7mD")!!
    private val chosen=TrackSelection("720p","aac")
    private val info=VideoInfo(link,link.key,"fixture","https://media.example/video",referer=link.url,userAgent="test",resolution="1280 × 720",fps=30f)
    private fun isolated(block: suspend (DownloadStore)->Unit) = runBlocking {
        val name="spec-test-${UUID.randomUUID()}"
        try { block(DownloadStore(context,name)) } finally { context.deleteSharedPreferences(name) }
    }
    @Test fun completedTaskRequiresConfirmationAndReplacementPersistsSelection() = isolated { store ->
        val task=store.add(link)
        store.update(task.id) { it.copy(status=TaskStatus.COMPLETED,uri="content://fixture/video",outputUris=listOf("content://fixture/audio")) }
        var stopped=false
        val deleted=mutableListOf<String>()
        val failure=runCatching { replaceTaskSpecification(store,task.id,info,chosen,false,{ stopped=true },deleted::add) }
        assertTrue(failure.exceptionOrNull() is FileReplacementConfirmation)
        assertFalse(stopped); assertTrue(deleted.isEmpty()); assertNotNull(store.get(task.id))
        val replacement=replaceTaskSpecification(store,task.id,info,chosen,true,{ stopped=true },{ assertTrue(stopped); deleted.add(it) })
        assertNull(store.get(task.id)); assertNotEquals(task.id,replacement.id)
        assertEquals(setOf("content://fixture/video","content://fixture/audio"),deleted.toSet())
        assertEquals(chosen,replacement.selection); assertEquals(30f,replacement.fps,0f)
        assertEquals("",replacement.uri); assertTrue(replacement.outputUris.isEmpty())
        store.remove(replacement.id) // active entries cannot be removed; make eligible first below.
        store.update(replacement.id) { it.copy(status=TaskStatus.CANCELLED) }; store.remove(replacement.id)
    }
    @Test fun workerFinishingDuringCancellationStillRequiresFileConfirmation() = isolated { store ->
        val task=store.add(link)
        val failure=runCatching { replaceTaskSpecification(store,task.id,info,chosen,false,{
            assertEquals(TaskStatus.CANCELLED,store.get(task.id)!!.status)
            store.update(task.id) { it.copy(status=TaskStatus.COMPLETED,uri="content://fixture/late") }
        },{ error("Must not delete without confirmation") }) }
        assertTrue(failure.exceptionOrNull() is FileReplacementConfirmation)
        assertNotNull(store.get(task.id))
        store.remove(task.id)
    }
    @Test fun failedFileDeletionKeepsOriginalAndDoesNotQueueReplacement() = isolated { store ->
        val task=store.add(link)
        store.update(task.id) { it.copy(status=TaskStatus.COMPLETED,uri="content://fixture/denied") }
        assertTrue(runCatching { replaceTaskSpecification(store,task.id,info,chosen,true,{}, { throw SecurityException("denied") }) }.isFailure)
        assertEquals(listOf(task.id),store.tasks.value.map { it.id })
        store.remove(task.id)
    }
    @Test fun activeTaskIsStoppedBeforeReplacementAndPausedQueueIsRespected() = isolated { store ->
        val task=store.add(link)
        store.pauseAll()
        var stopped=false
        val replacement=replaceTaskSpecification(store,task.id,info,chosen,false,{
            assertEquals(TaskStatus.CANCELLED,store.get(task.id)!!.status); stopped=true
        },{ error("No files to delete") })
        assertTrue(stopped); assertEquals(TaskStatus.PAUSED,replacement.status)
        assertEquals(chosen,replacement.selection)
        store.update(replacement.id) { it.copy(status=TaskStatus.CANCELLED) }; store.remove(replacement.id)
    }
}
