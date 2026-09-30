package com.daxiaamu.dbdown

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri

internal fun resolutionLabel(width: Int, height: Int): String =
    if(width > 0 && height > 0) "$width × $height" else ""

/** Reads local output only, off the main thread; also fills dimensions for older download records. */
internal fun savedResolution(context: Context, task: DownloadTask): String = runCatching {
    val uri = Uri.parse(task.uri)
    if(uri.scheme != "content") return ""
    if(task.mimeType.startsWith("image/")) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val size = resolutionLabel(bounds.outWidth, bounds.outHeight)
        if(size.isNotEmpty() && task.outputUris.size > 1) "首图 $size" else size
    } else {
        MediaMetadataRetriever().use { metadata ->
            metadata.setDataSource(context, uri)
            var width = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var height = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if(rotation % 180 != 0) { val swap = width; width = height; height = swap }
            resolutionLabel(width, height)
        }
    }
}.getOrDefault("")

internal fun savedFrameRate(context: Context, value: String): Float = runCatching {
    val uri=Uri.parse(value)
    if(uri.scheme != "content") return 0f
    val extractor=android.media.MediaExtractor()
    try {
        extractor.setDataSource(context,uri,null)
        for(i in 0 until extractor.trackCount) {
            val format=extractor.getTrackFormat(i)
            if(format.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                val fps=if(format.containsKey(android.media.MediaFormat.KEY_FRAME_RATE)) format.getNumber(android.media.MediaFormat.KEY_FRAME_RATE)?.toFloat() ?: 0f else 0f
                if(fps > 0 && fps.isFinite()) return fps
            }
        }
    } finally { extractor.release() }
    MediaMetadataRetriever().use {
        it.setDataSource(context,uri)
        frameRate(it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE).orEmpty())
    }
}.getOrDefault(0f)
