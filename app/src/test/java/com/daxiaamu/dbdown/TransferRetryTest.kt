package com.daxiaamu.dbdown

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException
import java.io.IOException
import org.json.JSONObject

class TransferRetryTest {
    @Test fun retriesAreBounded() = runTest {
        var attempts = 0
        val failure = runCatching { retryMediaTransfer { attempts++; throw SocketTimeoutException() } }
        assertTrue(failure.exceptionOrNull() is SocketTimeoutException)
        assertEquals(4,attempts)
    }
    @Test fun cancellationDiskFailuresAndPermanentHttpErrorsAreNotRetried() = runTest {
        listOf(CancellationException(),IOException("disk full"),MediaHttpException(403),MediaHttpException(404)).forEach { error ->
            var attempts = 0
            assertSame(error,runCatching { retryMediaTransfer { attempts++; throw error } }.exceptionOrNull())
            assertEquals(1,attempts)
        }
    }
    @Test fun biliBackupAddressesSupportBothApiSpellings() {
        assertEquals(listOf("https://cdn.example/video"),biliBackupUrls(JSONObject("""{"backupUrl":["http://cdn.example/video","https://cdn.example/video",null,""]}""")))
        assertEquals(listOf("https://cdn.example/audio"),biliBackupUrls(JSONObject("""{"backup_url":["https://cdn.example/audio"]}""")))
    }
}
