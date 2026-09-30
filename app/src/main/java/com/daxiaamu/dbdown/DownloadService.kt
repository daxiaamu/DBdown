package com.daxiaamu.dbdown

import android.app.*
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.media3.muxer.MediaMuxerCompat as MediaMuxer
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Call
import okhttp3.Request
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

class DownloadService : Service() {
    companion object {
        private var activeService: DownloadService? = null
        suspend fun awaitCancellation(ids: List<String>) {
            withContext(Dispatchers.Main.immediate) {
                activeService?.let { service -> if(service::queue.isInitialized) service.queue.cancelAndJoin(ids) }
            }
        }
        const val CHANNEL = "download_progress"
        const val RESULTS = "download_results"
        fun start(context: Context) = context.startForegroundService(Intent(context, DownloadService::class.java))
        fun pause(context: Context) = context.startForegroundService(Intent(context, DownloadService::class.java).setAction("pause"))
        fun resume(context: Context) = context.startForegroundService(Intent(context, DownloadService::class.java).setAction("resume"))
        fun cancel(context: Context, id: String) =
            context.startService(Intent(context, DownloadService::class.java).setAction("cancel").putExtra("id", id))
    }
    private val store get() = (application as DownloaderApp).store
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private lateinit var queue: DownloadQueue
    private val calls = ConcurrentHashMap<String, MutableSet<Call>>()
    private var lastStartId = 0
    private val transferClient = VideoResolver.client.newBuilder().callTimeout(0, TimeUnit.SECONDS).build()
    private val sizeClient = transferClient.newBuilder().callTimeout(8, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    private val sizeProbeSlots = Semaphore(4)
    private val notifications get() = getSystemService(NotificationManager::class.java)

    override fun onCreate() {
        super.onCreate()
        activeService = this
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "下载进度", NotificationManager.IMPORTANCE_LOW))
        notifications.createNotificationChannel(NotificationChannel(RESULTS, "下载结果", NotificationManager.IMPORTANCE_DEFAULT))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        startForeground(1, notification("下载队列", "正在准备下载"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if(!::queue.isInitialized) queue = DownloadQueue(scope,
            candidates = { store.tasks.value.asReversed().filter { it.status == TaskStatus.QUEUED }.map { it.id } },
            limit = { store.parallelism.value }, paused = { store.paused.value },
            perform = { id -> store.get(id)?.let { perform(it) } }, cancelCalls = ::cancelCalls,
            idle = { stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(lastStartId) })
        when(intent?.action) {
            "pause" -> { store.pauseAll(); queue.pause() }
            "resume" -> store.resumeAll()
            "cancel" -> intent.getStringExtra("id")?.let { id ->
                store.update(id) { if(it.status.pending) it.copy(status = TaskStatus.CANCELLED, speed = 0) else it }
                queue.cancel(id)
            }
        }
        if(observer == null) observer = scope.launch {
            combine(store.tasks.map { list -> list.map { it.id to it.status } }.distinctUntilChanged(),
                store.parallelism, store.paused) { _, _, _ -> Unit }.collect { queue.refresh() }
        }
        queue.refresh()
        return START_NOT_STICKY
    }
    private fun trackCall(id: String, call: Call, job: Job?) {
        calls.computeIfAbsent(id) { ConcurrentHashMap.newKeySet() }.add(call)
        if(job?.isActive != true || store.get(id)?.status?.active != true) call.cancel()
    }
    private fun cancelCalls(id: String) { calls[id]?.forEach { it.cancel() } }
    private suspend fun perform(task: DownloadTask) {
        val dir = File(cacheDir, "download-${task.id}").apply { mkdirs() }
        val worker = currentCoroutineContext()[Job]
        try {
            state(task.id, TaskStatus.RESOLVING)
            val link = Links.detect(task.source) ?: error("链接不受支持")
            val info = VideoResolver { trackCall(task.id, it, worker) }.resolve(link)
            currentCoroutineContext().ensureActive()
            if(!store.markResolved(task.id, info)) throw CancellationException("Task no longer active")
            withContext(Dispatchers.IO) {
                withTransferProgress(task.id,info,worker) { progress ->
                    if(info.images.isNotEmpty()) downloadAlbum(task,info,dir,progress)
                    else downloadVideo(task,info,dir,progress)
                }
            }
        } catch(e: CancellationException) {
            throw e
        } catch(e: Exception) {
            currentCoroutineContext().ensureActive()
            if(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) android.util.Log.e("DBDownload", "Download failed", e)
            if(store.get(task.id)?.status?.active == true) {
                val message = when(e) {
                    is java.net.UnknownHostException -> "网络不可用，请联网后重试"
                    is java.net.SocketTimeoutException -> "连接超时，请稍后重试"
                    is java.net.SocketException, is java.io.EOFException -> "下载连接被服务器中断，备用线路也未成功，请稍后重试"
                    is org.json.JSONException -> "平台返回的数据格式发生变化，请稍后重试"
                    else -> e.message?.take(180) ?: "下载失败，请重试"
                }
                store.update(task.id) { it.copy(status = TaskStatus.FAILED, error = message, speed = 0) }
                notifyResult(task.id, store.get(task.id)?.title ?: "下载失败", message)
            }
        } finally {
            calls.remove(task.id)
            withContext(NonCancellable + Dispatchers.IO) {
                if(store.get(task.id)?.status !in setOf(TaskStatus.PAUSED, TaskStatus.QUEUED)) dir.deleteRecursively()
            }
        }
    }
    private suspend fun withTransferProgress(id: String, info: VideoInfo, worker: Job?,
        block: suspend (TaskTransferProgress) -> Unit) = coroutineScope {
        val resources = downloadResources(info)
        val progress = TaskTransferProgress(resources)
        publishProgress(id,progress,0)
        val probes = launch {
            resources.filter { it.plan == null }.map { resource -> async {
                sizeProbeSlots.withPermit {
                    val size = probeResourceSize(sizeClient,resource,info.referer) { trackCall(id,it,worker) }
                    progress.discovered(resource.key,resource.url,size)
                    publishProgress(id,progress,null)
                }
            } }.awaitAll()
        }
        try { block(progress) } finally { probes.cancelAndJoin() }
    }

    private suspend fun downloadVideo(task: DownloadTask, info: VideoInfo, dir: File, progress: TaskTransferProgress) {
        val audioInfo=info.copy(userAgent=info.audioUserAgent ?: info.userAgent)
        val video = File(dir, "video.mp4")
        if(info.videoPlan != null) downloadSegments(info.videoPlan, video, info, task.id, progress, "video")
        else downloadWithFallback(listOf(info.video) + info.videoFallbacks,
            refreshOnUnavailable = if(info.source.platform == Platform.DOUYIN) {
                { DouyinPage.desktopVideoUrls(DouyinDesktop.detail(info.id.removePrefix("dy:")), info.source) }
            } else null) { url ->
            download(url, video, info, task.id, progress, "video")
        }
        val output = if(info.audio != null) {
            val audio = File(dir, "audio.m4a")
            if(info.audioPlan != null) downloadSegments(info.audioPlan, audio, audioInfo, task.id, progress, "audio")
            else download(info.audio, audio, audioInfo, task.id, progress, "audio")
            state(task.id, TaskStatus.MERGING)
            File(dir, "merged.mp4").also {
                if(info.audioCodec.equals("flac", true)) LosslessMuxer.merge(video, audio, it)
                else if(info.source.platform == Platform.YOUTUBE) LosslessMuxer.merge(video, audio, it, info.audioCodec)
                else mux(video, audio, it)
            }
        } else if(info.videoPlan != null) {
            state(task.id, TaskStatus.MERGING)
            File(dir, "merged.mp4").also { LosslessMuxer.remux(video, it) }
        } else video
        validateVideo(output)
        state(task.id, TaskStatus.SAVING)
        val uri = publish(output, info.title, task.id)
        val measured = savedResolution(this@DownloadService, task.copy(uri = uri.toString(), mimeType = "video/mp4"))
        store.update(task.id) { it.copy(status = TaskStatus.COMPLETED, uri = uri.toString(),
            resolution = measured.ifBlank { it.resolution }, bytes = progress.snapshot().bytes, total = progress.snapshot().bytes, speed = 0, error = "") }
        notifyResult(task.id, info.title, "已保存到 Movies/逗逼下载器", uri)
    }

    private suspend fun downloadAlbum(task: DownloadTask, info: VideoInfo, dir: File, progress: TaskTransferProgress) {
        val published = mutableListOf<Uri>()
        try {
            for((index, url) in info.images.withIndex()) {
                currentCoroutineContext().ensureActive()
                state(task.id, TaskStatus.DOWNLOADING)
                val image = File(dir, "slide-$index-image")
                download(url, image, info, task.id, progress, "image:$index")
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(image.absolutePath, bounds)
                check(bounds.outWidth > 0 && bounds.outHeight > 0) { "第 ${index + 1} 张图片无效" }
                val motion = info.imageVideos.getOrNull(index)
                val output: File
                val mime: String
                val extension: String
                if(motion != null) {
                    val video = File(dir, "slide-$index.mp4")
                    download(motion, video, info, task.id, progress, "motion:$index")
                    validateVideo(video)
                    state(task.id, TaskStatus.SAVING)
                    val jpeg = if(bounds.outMimeType == "image/jpeg") image else File(dir, "slide-$index.jpg").also { file ->
                        val bitmap = android.graphics.BitmapFactory.decodeFile(image.absolutePath) ?: error("Live 图封面无法解码")
                        try { file.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, it)) } }
                        finally { bitmap.recycle() }
                    }
                    output = File(dir, "slide-$index-MP.jpg")
                    // Write EXIF before appending the video; later metadata rewrites can remove the trailer.
                    androidx.exifinterface.media.ExifInterface(jpeg).apply {
                        setAttribute(androidx.exifinterface.media.ExifInterface.TAG_USER_COMMENT, "Oplus_8388608")
                        saveAttributes()
                    }
                    MotionPhoto.write(jpeg, video, output)
                    mime = "image/jpeg"; extension = "jpg"
                } else {
                    output = image
                    mime = bounds.outMimeType ?: error("图片格式无法识别")
                    extension = when(mime) { "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"; "image/gif" -> "gif"; "image/heif", "image/heic" -> "heic"; "image/avif" -> "avif"; else -> error("暂不支持此静态图片格式") }
                }
                state(task.id, TaskStatus.SAVING)
                published += publish(output, info.title, "${task.id.take(8)}-${index + 1}", mime, extension, image = true, motion = motion != null)
            }
            info.music?.let { url ->
                state(task.id, TaskStatus.DOWNLOADING)
                val audio = File(dir, "slides-music")
                download(url, audio, info, task.id, progress, "music")
                val audioTracks = android.media.MediaExtractor()
                try {
                    audioTracks.setDataSource(audio.absolutePath)
                    check(audioTracks.trackCount > 0 && (0 until audioTracks.trackCount).all {
                        audioTracks.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                    }) { "配乐资源不是独立音频，请重新获取作品" }
                } finally { audioTracks.release() }
                val metadata = android.media.MediaMetadataRetriever()
                val mime = try { metadata.setDataSource(audio.absolutePath); metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE) }
                    finally { metadata.release() }
                val extension = when(mime) { "audio/mpeg", "audio/mp3" -> "mp3"; "audio/mp4", "video/mp4" -> "m4a"; "audio/flac" -> "flac"; "audio/aac" -> "aac"; else -> error("配乐格式无法识别") }
                state(task.id, TaskStatus.SAVING)
                published += publish(audio, info.title, task.id, if(extension == "m4a") "audio/mp4" else mime!!, extension, audio = true)
            }
            currentCoroutineContext().ensureActive()
            store.update(task.id) { it.copy(status = TaskStatus.COMPLETED, uri = published.first().toString(),
                outputUris = published.map(Uri::toString), mimeType = contentResolver.getType(published.first()) ?: "image/jpeg",
                albumMode = AlbumMode.IMAGES, bytes = progress.snapshot().bytes, total = progress.snapshot().bytes, speed = 0, error = "") }
        } catch(e: Exception) {
            published.forEach { runCatching { contentResolver.delete(it, null, null) } }
            throw e
        }
        notifyResult(task.id, info.title, "${info.images.size} 张内容已保存${if(info.music != null) "，配乐单独保存" else ""}", published.first())
    }
    private suspend fun state(id: String, status: TaskStatus) {
        currentCoroutineContext().ensureActive()
        store.update(id) { if(it.status.active) it.copy(status = status, speed = 0) else it }
        notifyProgress()
    }
    private fun publishProgress(id: String, progress: TaskTransferProgress, speed: Long?, resolution: String = "") {
        store.update(id, save = speed == 0L) {
            val size=progress.snapshot()
            if(it.status in setOf(TaskStatus.DOWNLOADING,TaskStatus.MERGING,TaskStatus.SAVING))
                it.copy(bytes=size.bytes,total=size.total,speed=speed ?: it.speed,resolution=resolution.ifBlank { it.resolution })
            else it
        }
        notifyProgress()
    }

    private suspend fun download(url: String, file: File, info: VideoInfo, id: String, progress: TaskTransferProgress, resource: String) {
        val worker = currentCoroutineContext()[Job]
        val measureVideo = info.source.platform == Platform.DOUYIN && file.name == "video.mp4"
        var actualResolution = ""
        var nextProbe = 64 * 1024L
        if(measureVideo) store.update(id) { it.copy(resolution = "") }
        progress.begin(resource,url)
        ResumableTransfer(transferClient) { trackCall(id, it, worker) }.download(url, file,
            info.id + "|" + info.quality, info.userAgent, info.referer) { bytes, length, speed ->
            if(measureVideo && actualResolution.isBlank() && bytes >= nextProbe) {
                actualResolution = partialVideoResolution(file)
                nextProbe = if(nextProbe >= 512 * 1024L) Long.MAX_VALUE else nextProbe * 2
            }
            progress.update(resource,bytes,length)
            publishProgress(id,progress,speed,actualResolution)
        }
        progress.complete(resource,file.length())
        publishProgress(id,progress,0,actualResolution)
    }
    private suspend fun downloadSegments(plan: SegmentPlan, file: File, info: VideoInfo, id: String, progress: TaskTransferProgress, resource: String) {
        val worker=currentCoroutineContext()[Job]
        SegmentTransfer(transferClient, { trackCall(id,it,worker) }).download(plan,file,info.userAgent,info.referer) { bytes,length,speed ->
            progress.update(resource,bytes,length)
            publishProgress(id,progress,speed)
        }
        progress.complete(resource,file.length())
        publishProgress(id,progress,0)
    }
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private suspend fun mux(video: File, audio: File, output: File) {
        val extractors = listOf(MediaExtractor(), MediaExtractor())
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OUTPUT_FORMAT_MP4)
        var started = false
        try {
            val tracks = extractors.mapIndexed { index, extractor ->
                extractor.setDataSource(if(index == 0) video.absolutePath else audio.absolutePath)
                val type = if(index == 0) "video/" else "audio/"
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(type) == true
                } ?: error("下载的音视频轨道不完整")
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                Triple(extractor, muxer.addTrack(format), format)
            }
            muxer.start(); started = true
            // Interleave tracks: writing all video before any audio can exhaust the
            // platform muxer's track queues. Preserve sample order within each track.
            val capacity = tracks.maxOf { (_, _, format) ->
                if(format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                    maxOf(8 * 1024 * 1024, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                else 8 * 1024 * 1024
            }
            val buffer = ByteBuffer.allocateDirect(capacity)
            val bufferInfo = MediaCodec.BufferInfo()
            val audioTimestamps = AudioTimestamps()
            while(true) {
                currentCoroutineContext().ensureActive()
                val next = tracks.filter { it.first.sampleTime >= 0L }.minByOrNull { it.first.sampleTime } ?: break
                val (extractor, track, format) = next
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if(size < 0) { extractor.unselectTrack(extractor.sampleTrackIndex); continue }
                check(size <= capacity) { "视频帧大小超出支持范围" }
                val sampleTime = if(format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true)
                    audioTimestamps.next(extractor.sampleTime) else extractor.sampleTime
                bufferInfo.set(0, size, sampleTime,
                    if(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(track, buffer, bufferInfo)
                extractor.advance()
            }
            muxer.stop(); started = false
        } finally {
            if(started) runCatching { muxer.stop() }
            muxer.release()
            extractors.forEach { it.release() }
        }
    }
    private fun validateVideo(file: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            check((0 until extractor.trackCount).any {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }) { "服务器返回的文件不是可播放视频" }
        } finally { extractor.release() }
    }
    private suspend fun publish(file: File, title: String, id: String, mime: String = "video/mp4", extension: String = "mp4", image: Boolean = false, audio: Boolean = false, motion: Boolean = false): Uri {
        val clean = mediaBaseName(title)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$clean-${id.take(12)}${if(motion) "_MP" else ""}.$extension")
            put(MediaStore.Video.Media.MIME_TYPE, mime)
            put(MediaStore.Video.Media.RELATIVE_PATH, "${if(image) Environment.DIRECTORY_PICTURES else if(audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES}/逗逼下载器")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(if(image) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else if(audio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("无法创建视频文件")
        try {
            contentResolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input ->
                    val buffer = ByteArray(128*1024)
                    while(true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if(n < 0) break
                        output.write(buffer, 0, n)
                    }
                }
            } ?: error("无法写入视频文件")
            currentCoroutineContext().ensureActive()
            values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
            check(contentResolver.update(uri, values, null, null) == 1) { "无法完成视频保存" }
            return uri
        } catch(e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
    }
    private fun notification(title: String, text: String, progress: Int = -1): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra("downloads", true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title).setContentText(text).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
            .setProgress(100, progress.coerceAtLeast(0), progress < 0)
        val pause = PendingIntent.getService(this, 1, Intent(this, DownloadService::class.java).setAction("pause"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        builder.addAction(0, "全部暂停", pause)
        return builder.build()
    }
    private fun notifyProgress() {
        if(store.paused.value) return
        val active = store.tasks.value.filter { it.status.active && it.status != TaskStatus.QUEUED }
        val waiting = store.tasks.value.count { it.status == TaskStatus.QUEUED }
        val speed = active.sumOf { it.speed }
        val text = "${active.size} 个进行中 · $waiting 个等待 · ${formatBytes(speed)}/s"
        runCatching { notifications.notify(1, notification("下载队列", text)) }
    }
    private fun notifyResult(id: String, title: String, message: String, uri: Uri? = null) {
        val intent = Intent(this, MainActivity::class.java).putExtra("downloads", true)
        val pending = PendingIntent.getActivity(this, id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching { notifications.notify(id.hashCode(), NotificationCompat.Builder(this, RESULTS)
            .setSmallIcon(R.drawable.ic_download).setContentTitle(title).setContentText(message).setAutoCancel(true)
            .setContentIntent(pending).build()) }
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        store.tasks.value.filter { it.status.active }.forEach { t ->
            store.update(t.id) { it.copy(status = TaskStatus.INTERRUPTED, error = "系统后台下载时限已到，请回到应用重试", speed = 0) }
        }
        if(::queue.isInitialized) queue.close()
        stopSelf()
    }
    override fun onDestroy() {
        if(activeService === this) activeService = null
        if(::queue.isInitialized) queue.close()
        store.tasks.value.filter { it.status.active }.forEach { task ->
            store.update(task.id) { if(it.status.active) it.copy(status = TaskStatus.INTERRUPTED, speed = 0,
                error = "下载服务已停止，请重试") else it }
        }
        scope.cancel()
        calls.keys.toList().forEach(::cancelCalls)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
fun formatBytes(bytes: Long): String = when {
    bytes < 0 -> "大小未知"
    bytes >= 1024L*1024*1024 -> "%.2f GB".format(bytes.toDouble()/(1024*1024*1024))
    bytes >= 1024L*1024 -> "%.1f MB".format(bytes.toDouble()/(1024*1024))
    bytes >= 1024 -> "%.0f KB".format(bytes.toDouble()/1024)
    else -> "$bytes B"
}
