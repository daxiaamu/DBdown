package com.daxiaamu.dbdown

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

internal data class DeleteRequest(val ids: List<String>, val all: Boolean, val withFiles: Boolean? = null)
internal data class DeleteResult(val removed: Int, val failed: Int)

internal suspend fun deleteDownloadTasks(
    store: DownloadStore, ids: List<String>, withFiles: Boolean,
    stop: suspend (List<String>) -> Unit, deleteFile: (String) -> Unit
): DeleteResult {
    val targets = ids.distinct().filter { store.get(it) != null }
    // Make tasks ineligible for scheduling before cancelling and joining their workers.
    targets.forEach { id -> store.update(id) { if(it.status.pending) it.copy(status = TaskStatus.CANCELLED, speed = 0) else it } }
    stop(targets)
    return withContext(Dispatchers.IO) {
        var removed = 0
        var failed = 0
        targets.forEach { id ->
            val task = store.get(id) ?: return@forEach
            try {
                if(withFiles) (task.outputUris.ifEmpty { listOf(task.uri) }).filter(String::isNotBlank).distinct().forEach(deleteFile)
                store.remove(id)
                removed++
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) {
                // Keep a record when file deletion fails, so the user can retry it.
                failed++
                store.update(id) { it.copy(error = "部分文件无法删除，任务已保留，请重试") }
            }
        }
        DeleteResult(removed, failed)
    }
}
