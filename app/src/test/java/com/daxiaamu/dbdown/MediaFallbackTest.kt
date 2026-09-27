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
    @Test fun cancellationNeverStartsBackup() = runBlocking {
        val tried = mutableListOf<String>()
        val job = launch { downloadWithFallback(listOf("first", "second")) { url -> tried += url; cancel(); throw EOFException() } }
        job.join()
        assertEquals(listOf("first"), tried)
    }
}
