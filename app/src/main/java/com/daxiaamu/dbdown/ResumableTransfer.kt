package com.daxiaamu.dbdown

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.Properties

/** Resume only with an HTTP validator. A 200 response replaces the partial file, never appends to it. */
internal class ResumableTransfer(private val client: OkHttpClient, private val trackCall: (Call) -> Unit) {
    suspend fun download(url: String, file: File, mediaKey: String, userAgent: String, referer: String,
                         progress: (bytes: Long, total: Long, speed: Long) -> Unit) {
        val context = currentCoroutineContext()
        context.ensureActive()
        val metadataFile = File(file.path + ".resume")
        val metadata = Properties()
        runCatching { metadataFile.inputStream().use(metadata::load) }
        val sameMedia = metadata.getProperty("media") == mediaKey
        if(sameMedia && metadata.getProperty("url") == url && metadata.getProperty("complete") == "true" &&
            file.length() > 0 && file.length() == metadata.getProperty("length")?.toLongOrNull()) {
            progress(file.length(), file.length(), 0)
            return
        }
        val validator = metadata.getProperty("validator").orEmpty()
        var offset = if(sameMedia && validator.isNotBlank()) file.length() else 0L
        // A changed representation or an unsatisfiable Range gets one clean full request.
        repeat(2) {
            context.ensureActive()
            val request = Request.Builder().url(url).header("User-Agent", userAgent)
                .header("Referer", referer).header("Accept-Encoding", "identity")
            if(offset > 0) request.header("Range", "bytes=$offset-").header("If-Range", validator)
            val call = client.newCall(request.build()).also(trackCall)
            call.execute().use { response ->
                context.ensureActive()
                if(response.code == 416 && offset > 0) { offset = 0; return@repeat }
                if(response.code != 200 && response.code != 206) throw MediaHttpException(response.code)
                val body = response.body ?: error("视频内容为空")
                check(body.contentType()?.type !in listOf("text", "application") ||
                    body.contentType()?.subtype in listOf("octet-stream", "mp4")) { "视频地址失效，请重试" }
                val append = response.code == 206 && offset > 0
                val returnedValidator = if(validator.startsWith("\"")) response.header("ETag") else response.header("Last-Modified")
                if(append && returnedValidator != null && returnedValidator != validator) { offset = 0; return@repeat }
                val range = response.header("Content-Range")?.let {
                    Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(it)
                }
                if(response.code == 206) {
                    val start = range?.groupValues?.get(1)?.toLongOrNull()
                    val end = range?.groupValues?.get(2)?.toLongOrNull()
                    val size = range?.groupValues?.get(3)?.toLongOrNull()
                    check(start == offset && end != null && size != null && end >= start && size > end) { "服务器返回的续传范围无效，请重试" }
                    check(body.contentLength() < 0 || body.contentLength() == end - start + 1) { "服务器返回的续传长度无效，请重试" }
                }
                if(!append) offset = 0
                val responseLength = body.contentLength()
                val total = range?.groupValues?.get(3)?.toLongOrNull()
                    ?: if(responseLength >= 0) offset + responseLength else -1L
                val etag = response.header("ETag")?.takeUnless { it.startsWith("W/") }
                val nextValidator = etag ?: response.header("Last-Modified").orEmpty()
                // Opening with append=false truncates stale bytes before new metadata can be persisted.
                FileOutputStream(file, append).use { output ->
                    metadata.clear()
                    metadata.setProperty("media", mediaKey)
                    metadata.setProperty("url", url)
                    metadata.setProperty("validator", nextValidator)
                    metadata.setProperty("complete", "false")
                    metadataFile.outputStream().use { metadata.store(it, null) }
                    var written = offset
                    var lastBytes = written
                    var lastTick = System.nanoTime()
                    progress(written, total, 0)
                    body.byteStream().use { input ->
                        val buffer = ByteArray(128 * 1024)
                        while(true) {
                            context.ensureActive()
                            val count = input.read(buffer)
                            if(count < 0) break
                            context.ensureActive()
                            output.write(buffer, 0, count)
                            written += count
                            val now = System.nanoTime()
                            if(now - lastTick >= 400_000_000L) {
                                progress(written, total, ((written-lastBytes)*1_000_000_000.0/(now-lastTick)).toLong())
                                lastTick = now; lastBytes = written
                            }
                        }
                    }
                    check(written > 0 && (total < 0 || written == total)) { "文件传输不完整，请重试" }
                    output.flush()
                    context.ensureActive()
                    metadata.setProperty("complete", "true")
                    metadata.setProperty("length", written.toString())
                    metadataFile.outputStream().use { metadata.store(it, null) }
                    progress(written, written, 0)
                }
                return
            }
        }
        error("无法续传视频，请重试")
    }
}
