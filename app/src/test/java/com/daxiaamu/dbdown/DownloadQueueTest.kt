package com.daxiaamu.dbdown

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadQueueTest {
    @Test fun concurrencyLimitFillsSlotsAndLoweringDrainsWithoutCancelling() = runTest {
        val pending = (1..6).map { "$it" }.toMutableList()
        val release = (1..6).associate { "$it" to CompletableDeferred<Unit>() }
        val running = mutableSetOf<String>()
        var limit = 3
        var peak = 0
        val queue = DownloadQueue(backgroundScope, { pending.toList() }, { limit }, { false }, { id ->
            pending.remove(id); running.add(id); peak = maxOf(peak, running.size)
            try { release.getValue(id).await() } finally { running.remove(id) }
        }, {}, {})
        queue.refresh(); runCurrent()
        assertEquals(setOf("1", "2", "3"), running)
        limit = 1; queue.refresh(); runCurrent()
        assertEquals(3, running.size)
        release.getValue("1").complete(Unit); release.getValue("2").complete(Unit); runCurrent()
        assertEquals(setOf("3"), running)
        release.getValue("3").complete(Unit); runCurrent()
        assertEquals(setOf("4"), running)
        limit = 2; queue.refresh(); runCurrent()
        assertEquals(setOf("4", "5"), running)
        release.getValue("4").complete(Unit); runCurrent()
        assertEquals(setOf("5", "6"), running)
        assertEquals(3, peak)
        queue.close()
    }

    @Test fun fastPauseResumeWaitsForCleanupAndNewTasksStayPaused() = runTest {
        val pending = mutableListOf("a", "b", "c", "d")
        var paused = false
        val cleanup = CompletableDeferred<Unit>()
        val starts = mutableListOf<String>()
        val cancelledCalls = mutableSetOf<String>()
        val queue = DownloadQueue(backgroundScope, { pending.toList() }, { 3 }, { paused }, { id ->
            pending.remove(id); starts.add(id)
            try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } }
        }, { cancelledCalls.add(it) }, {})
        queue.refresh(); runCurrent()
        paused = true; pending.addAll(0, listOf("a", "b", "c")); queue.pause(); runCurrent()
        pending.add("e"); queue.refresh(); runCurrent()
        assertEquals(listOf("a", "b", "c"), starts)
        assertTrue(cancelledCalls.containsAll(listOf("a", "b", "c")))
        paused = false; queue.refresh(); runCurrent()
        assertEquals("Do not reuse files before cancelled worker cleanup ends", 3, starts.size)
        cleanup.complete(Unit); runCurrent()
        assertEquals(listOf("a", "b", "c", "a", "b", "c"), starts)
        queue.close()
    }
}
