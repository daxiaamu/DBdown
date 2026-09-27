package com.daxiaamu.dbdown.update

import kotlinx.coroutines.*

/** Owned by a single (main) dispatcher. Completed sessions are never used for manual checks. */
internal class SharedUpdateCheck<T>(
    private val scope: CoroutineScope, private val fetch: suspend () -> T,
    private val complete: (Result<T>, Boolean) -> Unit
) {
    private var running: Job? = null
    private var manualRequested = false
    fun request(manual: Boolean) {
        manualRequested = manualRequested || manual
        if(running?.isActive == true) return
        running = scope.launch(start = CoroutineStart.LAZY) {
            val result = try { Result.success(fetch()) } catch(e: CancellationException) { throw e }
            catch(e: Exception) { Result.failure(e) }
            val visible = manualRequested
            manualRequested = false
            running = null
            complete(result, visible)
        }.also { it.start() }
    }
}
