package com.daxiaamu.dbdown

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.media.MediaMetadataRetriever
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.*
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object AlbumExporter {
    // Preserve complete pictures without stretching. Mixed aspect ratios use letterboxing.
    suspend fun export(context: Context, images: List<File>, music: File, output: File) = withContext(Dispatchers.Main) {
        require(images.isNotEmpty())
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(images.first().absolutePath, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片无法解码" }
        val portrait = bounds.outHeight > bounds.outWidth
        val width = if(portrait) 720 else 1280
        val height = if(portrait) 1280 else 720
        val musicDurationMs = withContext(Dispatchers.IO) {
            val metadata = MediaMetadataRetriever()
            try {
                metadata.setDataSource(music.absolutePath)
                metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?.takeIf { it > 0 } ?: error("无法读取图集配乐时长，请重试")
            } finally { metadata.release() }
        }
        val durations = albumImageDurations(images.size, musicDurationMs)
        val clips = images.mapIndexed { index, file ->
            val imageBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, imageBounds)
            EditedMediaItem.Builder(MediaItem.Builder().setUri(Uri.fromFile(file)).setMimeType(imageBounds.outMimeType).setImageDurationMs(durations[index]).build())
                .setFrameRate(30).setRemoveAudio(true)
                .setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT))))
                .build()
        }
        val sequences = mutableListOf(EditedMediaItemSequence.withVideoFrom(clips))
        sequences += EditedMediaItemSequence.withAudioFrom(listOf(
            EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(music))).setRemoveVideo(true).build())).buildUpon()
            .setIsLooping(false).build()
        val composition = Composition.Builder(sequences).build()
        output.delete()
        var running: Transformer? = null
        try { suspendCancellableCoroutine<Unit> { continuation ->
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        if(continuation.isActive) continuation.resume(Unit)
                    }
                    override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                        if(continuation.isActive) continuation.resumeWithException(IllegalStateException("图集视频合成失败（${exception.errorCodeName}）：${exception.cause?.message?.take(120).orEmpty()}", exception))
                    }
                }).build()
            running = transformer
            try { transformer.start(composition, output.absolutePath) }
            catch(e: Exception) { if(continuation.isActive) continuation.resumeWithException(e) }
        } } finally { running?.cancel() }
    }
}
