package com.daxiaamu.dbdown

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val context get() = getApplication<DownloaderApp>()
    val store get() = context.store
    private val prefs = app.getSharedPreferences("settings", 0)
    var clipboardEnabled by mutableStateOf(prefs.getBoolean("clipboard", true))
        private set
    private val gate = PromptGate(prefs.getStringSet("handled", emptySet()).orEmpty().toMutableSet())
    private var selectedTab by mutableIntStateOf(0)
    var requestedTab by mutableIntStateOf(0)
        private set
    internal var consumedTabRequest = 0
    var tabRequest by mutableIntStateOf(0)
        private set
    var tab: Int
        get() = selectedTab
        set(value) {
            selectedTab = value
            requestedTab = value
            tabRequest++
        }
    // Gesture settlement updates selection without issuing another navigation command.
    fun onPageSettled(page: Int) { selectedTab = page }
    var revealTaskId by mutableStateOf<String?>(null)
        private set
    fun taskRevealed(id: String) { if(revealTaskId == id) revealTaskId = null }
    internal var deleteRequest by mutableStateOf<DeleteRequest?>(null)
        private set
    var deleting by mutableStateOf(false)
        private set
    internal fun requestDelete(ids: List<String>, all: Boolean = false, withFiles: Boolean? = null) {
        if(!deleting && ids.isNotEmpty()) deleteRequest = DeleteRequest(ids.toList(), all, withFiles)
    }
    internal fun chooseDeleteFiles() { deleteRequest = deleteRequest?.copy(withFiles = true) }
    internal fun dismissDeletion() { if(!deleting) deleteRequest = null }
    internal fun confirmDeletion(withFiles: Boolean) {
        val request = deleteRequest ?: return
        if(deleting) return
        deleting = true
        viewModelScope.launch {
            try {
                val result = deleteDownloadTasks(store, request.ids, withFiles, DownloadService::awaitCancellation) { value ->
                    context.contentResolver.delete(android.net.Uri.parse(value), null, null)
                }
                notice = if(result.failed == 0) "已删除 ${result.removed} 个任务" else "已删除 ${result.removed} 个任务，${result.failed} 个任务的文件无法删除，记录已保留"
                deleteRequest = null
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { notice = "删除未完成，请重试" }
            finally { deleting = false }
        }
    }
    var settings by mutableStateOf(false)
    var inputVisible by mutableStateOf(false)
    var albumMode by mutableStateOf(AlbumMode.IMAGES)
    var input by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    var clipboardSuggestion by mutableStateOf<VideoInfo?>(null)
    private var clipboardJob: Job? = null
    private var currentClipboard: String? = null

    fun setClipboard(enabled: Boolean) {
        clipboardEnabled = enabled
        prefs.edit().putBoolean("clipboard", enabled).apply()
        if(!enabled) { clipboardJob?.cancel(); clipboardSuggestion = null; currentClipboard = null }
    }
    fun inspectClipboard(text: String?) {
        if(!clipboardEnabled || inputVisible) return
        val link = text?.let(Links::detect)
        if(link == null) {
            clipboardJob?.cancel(); clipboardSuggestion = null
            currentClipboard = null
            return
        }
        if(link.key == currentClipboard) return
        currentClipboard = link.key
        clipboardJob?.cancel()
        clipboardSuggestion = null
        if(!gate.shouldShow(link.key)) return
        clipboardJob = viewModelScope.launch {
            try {
                val info = VideoResolver().resolve(link)
                if(!clipboardEnabled || inputVisible || currentClipboard != link.key) return@launch
                if(!gate.shouldShow(info.id)) return@launch
                if(store.tasks.value.any { it.key == info.id && (it.status.pending || it.status == TaskStatus.COMPLETED) }) return@launch
                clipboardSuggestion = info
                rememberHandled(link.key); rememberHandled(info.id)
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { currentClipboard = null /* Retry next foreground transition; never prompt for unverifiable content. */ }
        }
    }
    private fun rememberHandled(key: String) {
        gate.handled(key)
        prefs.edit().putStringSet("handled", gate.keys()).apply()
    }
    fun openInput(text: String = "") {
        clipboardJob?.cancel()
        clipboardSuggestion = null
        input = text.take(16000); error = null; inputVisible = true
    }
    fun onShare(text: String) {
        settings = false
        openInput(text)
        if(Links.detect(text) == null) error = "没有识别到 B 站视频或抖音作品链接"
    }
    fun submit(): Boolean {
        val link = Links.detect(input)
        if(link == null) { error = "请粘贴 B 站视频或抖音作品链接，也支持完整分享文案、BV / AV 号"; return false }
        val task = store.add(link, albumMode)
        if(task == null) { error = "这个作品已经在下载列表中"; return false }
        revealTaskId = task.id
        rememberHandled(link.key)
        if(!start(task.id)) { error = store.get(task.id)?.error; return false }
        inputVisible = false; tab = 1; settings = false
        return true
    }
    fun downloadSuggestion(mode: AlbumMode = AlbumMode.IMAGES): Boolean {
        val info = clipboardSuggestion ?: return false
        val task = store.add(info.source, mode)
        clipboardSuggestion = null
        if(task == null) { notice = "这个作品已经在下载列表中"; return false }
        revealTaskId = task.id
        store.update(task.id) { it.copy(title = info.title, quality = info.quality) }
        tab = 1; settings = false
        return start(task.id)
    }
    private fun start(id: String): Boolean = try {
        if(!store.paused.value) DownloadService.start(context)
        true
    } catch(e: Exception) {
        store.update(id) { it.copy(status = TaskStatus.FAILED, error = "无法启动后台下载，请回到应用后重试") }
        false
    }
    fun retry(id: String) {
        if(deleting) return
        val task = store.get(id) ?: return
        if(task.status.pending) return
        if(store.tasks.value.any { it.id != id && it.key == task.key && it.albumMode == task.albumMode && (it.status.pending || it.status == TaskStatus.COMPLETED) }) {
            notice = "这个作品已经在下载列表中"; return
        }
        store.update(id) { it.copy(status = if(store.paused.value) TaskStatus.PAUSED else TaskStatus.QUEUED, error = "", bytes = 0, total = -1, speed = 0) }
        start(id)
    }
    fun pauseDownloads() {
        runCatching { DownloadService.pause(context) }.onFailure { notice = "无法暂停下载，请重试" }
    }
    fun resumeDownloads() {
        if(deleting) return
        runCatching { DownloadService.resume(context) }.onFailure { notice = "无法继续下载，请重试" }
    }
    fun setParallelism(count: Int) { store.setParallelism(count) }
    fun cancel(id: String) {
        runCatching { DownloadService.cancel(context, id) }.onFailure {
            store.update(id) { it.copy(status = TaskStatus.CANCELLED, speed = 0) }
        }
    }
}
