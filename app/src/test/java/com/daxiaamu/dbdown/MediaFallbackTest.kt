package com.daxiaamu.dbdown

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException

class MediaFallbackTest {
    @Test fun closedConnectionMovesToNextSourceAndStopsOnSuccess() = runBlocking {
        val tried = mutableListOf<String>()
        downloadWithFallback(listOf("first", "first", "second", "unused")) { url ->
            tried += url; if(url == "first") throw EOFException("connection closed")
        }
        assertEquals(listOf("first", "second"), tried)
    }
    @Test fun unavailableSourcesRefreshOnceAfterTryingAllCandidates() = runBlocking {
        val tried = mutableListOf<String>()
        var refreshes = 0
        downloadWithFallback(listOf("old-a", "old-b"), refreshOnUnavailable = {
            refreshes++; listOf("new")
        }) { url -> tried += url; if(url != "new") throw MediaHttpException(404) }
        assertEquals(listOf("old-a", "old-b", "new"), tried)
        assertEquals(1, refreshes)
    }
    @Test fun refreshedFailureDoesNotLoopAndOtherFailuresDoNotRefresh() = runBlocking {
        for(code in listOf(404, 403)) {
            var refreshes = 0
            val result = runCatching {
                downloadWithFallback(listOf("old"), refreshOnUnavailable = { refreshes++; listOf("new") }) {
                    throw MediaHttpException(code)
                }
            }
            assertEquals(code, (result.exceptionOrNull() as MediaHttpException).code)
            assertEquals(if(code == 404) 1 else 0, refreshes)
        }
    }
    @Test fun cancellationNeverStartsBackup() = runBlocking {
        val tried = mutableListOf<String>()
        val job = launch { downloadWithFallback(listOf("first", "second")) { url -> tried += url; cancel(); throw EOFException() } }
        job.join()
        assertEquals(listOf("first"), tried)
    }
}
