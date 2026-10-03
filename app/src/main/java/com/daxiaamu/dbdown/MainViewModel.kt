package com.daxiaamu.dbdown

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    internal var homeMessages by mutableStateOf(HomeMessages.Empty)
        private set
    internal suspend fun refreshHomeMessages() {
        try {
            homeMessages = com.daxiaamu.dbdown.update.UpdateSource(BuildConfig.UPDATE_REPOSITORY, BuildConfig.UPDATE_BRANCH)
                .repositoryJson("config/home-messages.json", HomeMessages::parse)
        } catch(e: CancellationException) { throw e }
        catch(_: Exception) { homeMessages = HomeMessages.Empty }
    }
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
    internal val specificationEditor by lazy { SpecificationEditor(context,viewModelScope) { task ->
        revealTaskId=task.id; tab=1; settings=false; start(task.id)
    } }
    var settings by mutableStateOf(false)
    var inputVisible by mutableStateOf(false)
    var input by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    internal val clipboard = ClipboardSuggestions(viewModelScope, { VideoResolver().resolve(it) }, gate::shouldShow, ::rememberHandled)
    internal val clipboardPrompt get() = clipboard.state

    fun setClipboard(enabled: Boolean) {
        clipboardEnabled = enabled
        prefs.edit().putBoolean("clipboard", enabled).apply()
        if(!enabled) clipboard.clear()
    }
    internal fun inspectClipboard(content: ClipboardContent?) {
        if(clipboardEnabled && !inputVisible) clipboard.inspect(content?.text, content?.timestamp ?: 0)
    }
    fun dismissClipboard() = clipboard.dismiss()
    fun retryClipboard() = clipboard.retry()
    private fun rememberHandled(key: String) {
        gate.handled(key)
        prefs.edit().putStringSet("handled", gate.keys()).apply()
    }
    fun openInput(text: String = "") {
        clipboard.clear()
        input = text.take(16000); error = null; inputVisible = true
    }
    fun onShare(text: String) {
        settings = false
        openInput(text)
        if(Links.detect(text) == null) error = "没有识别到 B 站、抖音、YouTube 或微博 视频"
    }
    fun submit(): Boolean {
        val link = Links.detect(input)
        if(link == null) { error = "请粘贴 B 站、抖音、YouTube 或微博 链接，也支持分享文案、BV / AV 号和 YouTube 视频 ID"; return false }
        val task = enqueue(link)
        if(!start(task.id)) { error = store.get(task.id)?.error; return false }
        inputVisible = false; tab = 1; settings = false
        return true
    }
    fun downloadSuggestion(): Boolean {
        val info=(clipboardPrompt as? ClipboardPrompt.Ready)?.info ?: return false
        clipboard.clear()
        val task=enqueue(info.source,info)
        tab=1; settings=false
        return start(task.id)
    }
    private fun enqueue(link: VideoLink, info: VideoInfo? = null): DownloadTask {
        val task = store.add(link)
        if(info != null) store.update(task.id) {
            it.copy(title = info.title, quality = info.quality, resolution = info.resolution)
        }
        revealTaskId = task.id
        return task
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
        if(store.retry(id)) start(id)
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
