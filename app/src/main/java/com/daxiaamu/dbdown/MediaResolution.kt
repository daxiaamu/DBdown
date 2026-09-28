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
