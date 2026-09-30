package com.daxiaamu.dbdown

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class FileReplacementConfirmation : IllegalStateException()

/** Stop and join the old worker before deleting files or scheduling the replacement. */
internal suspend fun replaceTaskSpecification(store: DownloadStore, id: String, info: VideoInfo,
    selection: TrackSelection, deleteConfirmed: Boolean, stop: suspend (List<String>) -> Unit,
    deleteFile: (String) -> Unit): DownloadTask {
    fun outputs(task: DownloadTask) = (task.outputUris + task.uri).filter(String::isNotBlank).distinct()
    val original = store.get(id) ?: error("任务已删除")
    if((original.status == TaskStatus.COMPLETED || outputs(original).isNotEmpty()) && !deleteConfirmed)
        throw FileReplacementConfirmation()
    store.update(id) { if(it.status.pending) it.copy(status=TaskStatus.CANCELLED,speed=0) else it }
    stop(listOf(id))
    val stopped = store.get(id) ?: error("任务已删除")
    // A save can finish while the user is choosing or while cancellation is being delivered.
    if((stopped.status == TaskStatus.COMPLETED || outputs(stopped).isNotEmpty()) && !deleteConfirmed)
        throw FileReplacementConfirmation()
    withContext(Dispatchers.IO) {
        outputs(stopped).forEach(deleteFile)
        store.remove(id)
    }
    val task = store.add(info.source,selection=selection)
    store.update(task.id) { it.copy(title=info.title,quality=info.quality,resolution=info.resolution,fps=info.fps) }
    return store.get(task.id)!!
}
