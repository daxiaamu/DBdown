package com.daxiaamu.dbdown

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Retry only transient network failures. Cancellation, invalid media and disk failures must escape. */
internal suspend fun <T> retryMediaTransfer(block: suspend () -> T): T {
    repeat(4) { attempt ->
        currentCoroutineContext().ensureActive()
        try { return block() }
        catch(error: Exception) {
            currentCoroutineContext().ensureActive()
            val transient = error is SocketTimeoutException || error is SocketException ||
                error is EOFException || error is UnknownHostException ||
                (error is MediaHttpException && error.code in setOf(408, 429, 500, 502, 503, 504))
            if(!transient || attempt == 3) throw error
            delay(1000L shl attempt)
        }
    }
    error("Unreachable")
}
