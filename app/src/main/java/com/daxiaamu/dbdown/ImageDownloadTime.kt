package com.daxiaamu.dbdown

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal const val IMAGE_DOWNLOAD_TIME = "imageDownloadTime"

/** Prepare metadata on a copy, before a Live photo's video is appended. */
internal fun imageWithDownloadTime(source: File, mime: String, timestamp: Long?): File {
    if(timestamp == null || mime !in setOf("image/jpeg", "image/png", "image/webp")) return source
    val output = File(source.parentFile, source.name + ".dated")
    source.copyTo(output, overwrite = true)
    val tags = imageTimeTags(timestamp)
    ExifInterface(output).apply {
        setAttribute(ExifInterface.TAG_DATETIME, tags.date)
        setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, tags.date)
        setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, tags.date)
        setAttribute(ExifInterface.TAG_OFFSET_TIME, tags.offset)
        setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, tags.offset)
        setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, tags.offset)
        setAttribute(ExifInterface.TAG_SUBSEC_TIME, tags.millis)
        setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, tags.millis)
        setAttribute(ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, tags.millis)
        saveAttributes()
    }
    return output
}

internal data class ImageTimeTags(val date: String, val offset: String, val millis: String)

internal fun imageTimeTags(timestamp: Long, zone: ZoneId = ZoneId.systemDefault()): ImageTimeTags {
    val time = Instant.ofEpochMilli(timestamp).atZone(zone)
    return ImageTimeTags(
        DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.ROOT).format(time),
        DateTimeFormatter.ofPattern("xxx", Locale.ROOT).format(time),
        String.format(Locale.ROOT, "%03d", time.nano / 1_000_000)
    )
}
