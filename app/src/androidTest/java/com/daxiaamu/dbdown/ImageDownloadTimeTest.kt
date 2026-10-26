package com.daxiaamu.dbdown

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ImageDownloadTimeTest {
    @Test fun downloadedLiveAlbumPublishesMatchingExifAndGalleryTime() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("imageTimeOnline") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = (context.applicationContext as DownloaderApp).store
        check(store.tasks.value.none { it.status.pending }) { "Do not interrupt existing downloads" }
        val prefs = context.getSharedPreferences("settings", 0)
        val previous = prefs.getBoolean(IMAGE_DOWNLOAD_TIME, false)
        val paused = store.paused.value
        prefs.edit().putBoolean(IMAGE_DOWNLOAD_TIME, true).commit()
        try {
            val activity = instrumentation.startActivitySync(android.content.Intent(context, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            try {
                var id = ""
                val start = System.currentTimeMillis()
                instrumentation.runOnMainSync {
                    store.resumeAll()
                    id = store.add(Links.detect("https://v.douyin.com/Bt6e6Qmw2n4/")!!).id
                    DownloadService.start(context)
                }
                val deadline = start + 180_000
                while(store.get(id)!!.status.pending && System.currentTimeMillis() < deadline) Thread.sleep(500)
                val task = store.get(id)!!
                assertEquals(task.error, TaskStatus.COMPLETED, task.status)
                val images = task.outputUris.map(android.net.Uri::parse).filter {
                    context.contentResolver.getType(it)?.startsWith("image/") == true
                }
                assertEquals(2, images.size)
                images.forEach { uri ->
                    val taken = context.contentResolver.query(uri,
                        arrayOf(android.provider.MediaStore.Images.Media.DATE_TAKEN), null, null, null)!!.use {
                        assertTrue(it.moveToFirst()); it.getLong(0)
                    }
                    assertTrue(taken in start..System.currentTimeMillis())
                    context.contentResolver.openInputStream(uri)!!.use { input ->
                        val exif = ExifInterface(input)
                        assertEquals(imageTimeTags(taken).date, exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
                        assertEquals(imageTimeTags(taken).offset, exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))
                        assertEquals(imageTimeTags(taken).millis, exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL))
                        assertEquals("Oplus_8388608", exif.getAttribute(ExifInterface.TAG_USER_COMMENT))
                    }
                    val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    val text = bytes.toString(Charsets.ISO_8859_1)
                    val size = Regex("Item:Length=\"(\\d+)\"").find(text)!!.groupValues[1].toInt()
                    val video = File(context.cacheDir, "image-time-verify.mp4")
                    try {
                        video.writeBytes(bytes.takeLast(size).toByteArray())
                        val extractor = android.media.MediaExtractor()
                        try {
                            extractor.setDataSource(video.path)
                            assertTrue((0 until extractor.trackCount).any {
                                extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true
                            })
                        } finally { extractor.release() }
                    } finally { video.delete() }
                }
                instrumentation.sendStatus(0, android.os.Bundle().apply {
                    putString("stream", "Saved 2 Live Photos: EXIF time matches gallery time; embedded videos are playable\n")
                })
            } finally { instrumentation.runOnMainSync { activity.finish() } }
        } finally {
            prefs.edit().putBoolean(IMAGE_DOWNLOAD_TIME, previous).commit()
            if(paused) store.pauseAll()
        }
    }

    @Test fun staticFormatsPreservePixelsAndSourceWhileUpdatingExif() = temporary { dir ->
        val time = Instant.parse("2026-10-24T08:09:10.123Z").toEpochMilli()
        listOf("image/jpeg" to Bitmap.CompressFormat.JPEG, "image/png" to Bitmap.CompressFormat.PNG,
            "image/webp" to Bitmap.CompressFormat.WEBP_LOSSLESS).forEachIndexed { index, (mime, format) ->
            val source = picture(dir, "$index", format)
            val bytes = source.readBytes()
            ExifInterface(source).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, "6")
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2000:01:01 00:00:00")
                saveAttributes()
            }
            val original = source.readBytes()
            val output = imageWithDownloadTime(source, mime, time)
            assertArrayEquals(original, source.readBytes())
            ExifInterface(output).apply {
                assertEquals(imageTimeTags(time).date, getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
                assertEquals(imageTimeTags(time).offset, getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))
                assertEquals("123", getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL))
                assertEquals("6", getAttribute(ExifInterface.TAG_ORIENTATION))
            }
            val before = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val after = android.graphics.BitmapFactory.decodeFile(output.path)
            try { assertTrue(before.sameAs(after)) } finally { before.recycle(); after.recycle() }
            assertEquals(source, imageWithDownloadTime(source, mime, null))
        }
    }

    @Test fun livePhotoRetainsTimestampAndCompleteMotionTrailer() = temporary { dir ->
        val time = Instant.parse("2026-10-24T08:09:10.123Z").toEpochMilli()
        val jpeg = imageWithDownloadTime(picture(dir, "cover", Bitmap.CompressFormat.JPEG), "image/jpeg", time)
        ExifInterface(jpeg).apply {
            setAttribute(ExifInterface.TAG_USER_COMMENT, "Oplus_8388608")
            saveAttributes()
        }
        val trailer = ByteArray(2048) { (it % 251).toByte() }
        val video = File(dir, "motion.mp4").apply { writeBytes(trailer) }
        val output = File(dir, "live.jpg")
        MotionPhoto.write(jpeg, video, output)
        assertArrayEquals(trailer, output.readBytes().takeLast(trailer.size).toByteArray())
        ExifInterface(output).apply {
            assertEquals(imageTimeTags(time).date, getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
            assertEquals("Oplus_8388608", getAttribute(ExifInterface.TAG_USER_COMMENT))
        }
        assertTrue(output.readBytes().toString(Charsets.ISO_8859_1).contains("Item:Length=\"2048\""))
    }

    @Test fun unsupportedFormatsRemainUntouched() = temporary { dir ->
        val file = File(dir, "original.avif").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertEquals(file, imageWithDownloadTime(file, "image/avif", System.currentTimeMillis()))
        assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
    }

    private fun picture(dir: File, name: String, format: Bitmap.CompressFormat): File {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff336699.toInt()) }
        return File(dir, name).also { file ->
            try { file.outputStream().use { assertTrue(bitmap.compress(format, 95, it)) } }
            finally { bitmap.recycle() }
        }
    }
    private fun temporary(test: (File) -> Unit) {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "image-time-${System.nanoTime()}").apply { mkdirs() }
        try { test(dir) } finally { dir.deleteRecursively() }
    }
}
