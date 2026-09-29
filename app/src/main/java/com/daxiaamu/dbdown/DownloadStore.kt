package com.daxiaamu.dbdown

import android.app.Application
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class AlbumMode { IMAGES, VIDEO }

enum class TaskStatus(val label: String) {
    PAUSED("已暂停"), QUEUED("等待下载"), RESOLVING("正在解析"), DOWNLOADING("正在下载"), MERGING("正在合成"),
    SAVING("保存到相册"), COMPLETED("已完成"), FAILED("下载失败"), CANCELLED("已取消"), INTERRUPTED("下载中断");
    val pending get() = active || this == PAUSED
    val active get() = this in setOf(QUEUED, RESOLVING, DOWNLOADING, MERGING, SAVING)
}
data class DownloadTask(
    val id: String = UUID.randomUUID().toString(), val source: String, val key: String,
    val platform: Platform, val title: String = "正在获取视频信息",
    val status: TaskStatus = TaskStatus.QUEUED, val bytes: Long = 0, val total: Long = -1,
    val speed: Long = 0, val quality: String = "", val uri: String = "", val error: String = "",
    val created: Long = System.currentTimeMillis(), val albumMode: AlbumMode = AlbumMode.IMAGES,
    val outputUris: List<String> = emptyList(), val mimeType: String = "video/mp4", val resolution: String = ""
) {
    val progress: Float get() = if(total > 0) (bytes.toDouble()/total).toFloat().coerceIn(0f, 1f) else 0f
}
class DownloadStore(context: Context, preferencesName: String = "downloads") {
    private val cacheDirectory = context.cacheDir
    private val prefs = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val _paused = MutableStateFlow(prefs.getBoolean("paused", false))
    val paused = _paused.asStateFlow()
    private val _parallelism = MutableStateFlow(prefs.getInt("parallelism", 3).coerceIn(1, 6))
    val parallelism = _parallelism.asStateFlow()
    private val _tasks = MutableStateFlow(read())
    val tasks = _tasks.asStateFlow()
    private fun read(): List<DownloadTask> = runCatching {
        val array = JSONArray(prefs.getString("tasks", "[]"))
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val state = TaskStatus.valueOf(o.getString("status"))
            DownloadTask(o.getString("id"), o.getString("source"), o.getString("key"),
                Platform.valueOf(o.getString("platform")), o.getString("title"),
                if(state.active) TaskStatus.INTERRUPTED else state, o.optLong("bytes"), o.optLong("total", -1),
                quality = o.optString("quality"), uri = o.optString("uri"),
                error = if(state.active) "上次下载被系统中断，点击重试" else o.optString("error"), created = o.optLong("created"),
                albumMode = runCatching { AlbumMode.valueOf(o.optString("albumMode")) }.getOrDefault(AlbumMode.IMAGES),
                outputUris = o.optJSONArray("outputUris")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                mimeType = o.optString("mimeType", "video/mp4"), resolution = o.optString("resolution"))
        }
    }.getOrDefault(emptyList())
    @Synchronized fun add(link: VideoLink, albumMode: AlbumMode = AlbumMode.IMAGES): DownloadTask? {
        if (_tasks.value.any { it.key == link.key && it.albumMode == albumMode && (it.status.pending || it.status == TaskStatus.COMPLETED) }) return null
        val task = DownloadTask(source = link.url, key = link.key, platform = link.platform, albumMode = albumMode, status = if(_paused.value) TaskStatus.PAUSED else TaskStatus.QUEUED)
        _tasks.value = listOf(task) + _tasks.value
        persist()
        return task
    }
    @Synchronized fun update(id: String, save: Boolean = true, transform: (DownloadTask) -> DownloadTask) {
        _tasks.value = _tasks.value.map { if(it.id == id) transform(it) else it }
        if(save) persist()
    }
    fun get(id: String) = _tasks.value.find { it.id == id }
    @Synchronized fun remove(id: String) {
        val task = get(id) ?: return
        if(task.status.pending) return
        _tasks.value = _tasks.value.filterNot { it.id == id }; persist()
        // Removed records never reuse their UUID, so cleanup cannot race a new task.
        java.io.File(cacheDirectory, "download-$id").deleteRecursively()
    }
    @Synchronized fun setParallelism(value: Int) {
        _parallelism.value = value.coerceIn(1, 6)
        prefs.edit().putInt("parallelism", _parallelism.value).apply()
    }
    @Synchronized fun pauseAll() {
        _paused.value = true
        _tasks.value = _tasks.value.map { if(it.status.active) it.copy(status = TaskStatus.PAUSED, speed = 0) else it }
        persist()
    }
    @Synchronized fun resumeAll() {
        _paused.value = false
        _tasks.value = _tasks.value.map { if(it.status == TaskStatus.PAUSED) it.copy(status = TaskStatus.QUEUED, error = "", speed = 0) else it }
        persist()
    }
    // Resolve aliases atomically: concurrent short/direct links cannot publish the same video twice.
    @Synchronized fun claim(id: String, info: VideoInfo): Boolean {
        val task = get(id) ?: return false
        if(!task.status.active) return false
        val mode = if(info.separateAlbumMusic) AlbumMode.IMAGES else effectiveAlbumMode(task.albumMode, info.images.isNotEmpty(), info.music)
        if(_tasks.value.any { it.id != id && it.key == info.id && it.albumMode == mode &&
            (it.status == TaskStatus.COMPLETED || it.status in setOf(TaskStatus.DOWNLOADING, TaskStatus.MERGING, TaskStatus.SAVING)) }) {
            error("这个视频已在下载列表中")
        }
        update(id) { it.copy(title = info.title, key = info.id, quality = if(info.images.isEmpty()) info.quality else "${info.images.size} 张图片 · ${if(mode == AlbumMode.IMAGES) "图片" else "合成视频"}", albumMode = mode, resolution = info.resolution, status = TaskStatus.DOWNLOADING) }
        return true
    }
    private fun persist() {
        val array = JSONArray()
        _tasks.value.forEach { t -> array.put(JSONObject().apply {
            put("id", t.id); put("source", t.source); put("key", t.key); put("platform", t.platform.name)
            put("title", t.title); put("status", t.status.name); put("bytes", t.bytes); put("total", t.total)
            put("albumMode", t.albumMode.name); put("outputUris", JSONArray(t.outputUris)); put("mimeType", t.mimeType)
            put("resolution", t.resolution); put("quality", t.quality); put("uri", t.uri); put("error", t.error); put("created", t.created)
        }) }
        prefs.edit().putBoolean("paused", _paused.value).putString("tasks", array.toString()).apply()
    }
}
class DownloaderApp : Application() {
    lateinit var updates: com.daxiaamu.dbdown.update.UpdateManager
    lateinit var store: DownloadStore
    override fun onCreate() { super.onCreate(); WebAccounts.initialize(this); store = DownloadStore(this); updates = com.daxiaamu.dbdown.update.UpdateManager(this) }
}
