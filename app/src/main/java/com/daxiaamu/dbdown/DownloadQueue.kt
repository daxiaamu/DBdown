package com.daxiaamu.dbdown

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** All scheduling runs on the owner's dispatcher; a cancelled worker retains its slot until cleanup finishes. */
internal class DownloadQueue(
    private val scope: CoroutineScope,
    private val candidates: () -> List<String>,
    private val limit: () -> Int,
    private val paused: () -> Boolean,
    private val perform: suspend (String) -> Unit,
    private val cancelCalls: (String) -> Unit,
    private val idle: () -> Unit
) {
    private val workers = linkedMapOf<String, Job>()
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private var closed = false
    init {
        scope.launch {
            for(event in changes) {
                workers.entries.removeAll { it.value.isCompleted }
                if(!paused()) {
                    for(id in candidates()) {
                        if(workers.size >= limit()) break
                        if(id in workers) continue
                        val job = scope.launch(start = CoroutineStart.LAZY) { perform(id) }
                        workers[id] = job
                        job.invokeOnCompletion { refresh() }
                        job.start()
                    }
                }
                if(workers.isEmpty() && (paused() || candidates().isEmpty())) idle()
            }
        }
    }
    fun contains(id: String) = id in workers
    fun refresh() { if(!closed) changes.trySend(Unit) }
    fun cancel(id: String) { workers[id]?.cancel(); cancelCalls(id); refresh() }
    suspend fun cancelAndJoin(ids: List<String>) {
        val pending = ids.mapNotNull { workers[it] }
        ids.forEach(::cancel)
        pending.joinAll()
    }
    fun pause() { workers.keys.toList().forEach(::cancel); refresh() }
    fun close() { closed = true; workers.keys.toList().forEach(::cancel); changes.close() }
}
