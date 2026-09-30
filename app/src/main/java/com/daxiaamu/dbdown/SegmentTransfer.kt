package com.daxiaamu.dbdown

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Completed fragments are atomic cache entries. Pause resumes at the next unfinished fragment. */
internal class SegmentTransfer(client: OkHttpClient, private val track: (Call) -> Unit,
    private val allowed: (String) -> Boolean = ::youtubeMediaUrl) {
    private val client = client.newBuilder().cookieJar(CookieJar.NO_COOKIES).addNetworkInterceptor { chain ->
        require(allowed(chain.request().url.toString())) { "Unsupported segment redirect" }
        chain.proceed(chain.request())
    }.build()
    suspend fun download(plan: SegmentPlan, output: File, ua: String, referer: String,
        progress: (Long,Long,Long) -> Unit) {
        retryMediaTransfer { downloadOnce(plan, output, ua, referer, progress) }
    }
    private suspend fun downloadOnce(plan: SegmentPlan, output: File, ua: String, referer: String,
        progress: (Long,Long,Long) -> Unit) {
        require(plan.segments.isNotEmpty())
        val context = currentCoroutineContext()
        val digest = MessageDigest.getInstance("SHA-256")
        (listOf(plan.identity) + plan.segments.map { it.toString() }).forEach { digest.update(it.toByteArray()); digest.update(0) }
        val key = digest.digest().joinToString("") { "%02x".format(it) }.take(20)
        val directory = File(output.parentFile,output.name + ".segments-" + key).apply { mkdirs() }
        val keys = mutableMapOf<String,ByteArray>()
        val total = if(plan.segments.all { it.length > 0 && it.keyUrl == null }) plan.segments.sumOf { it.length } else -1L
        var written = 0L
        var lastBytes = 0L
        var lastTick = System.nanoTime()
        fun report(force: Boolean = false) {
            val now = System.nanoTime()
            if(force || now - lastTick >= 400_000_000L) {
                val speed = if(force) 0 else ((written-lastBytes)*1_000_000_000.0/(now-lastTick)).toLong()
                progress(written,total,speed); lastBytes=written; lastTick=now
            }
        }
        for((index,part) in plan.segments.withIndex()) {
            context.ensureActive()
            require(allowed(part.url) && part.start >= 0 && part.length != 0L)
            val complete = File(directory,"$index.complete")
            if(complete.isFile && complete.length() > 0) { written += complete.length(); report(true); continue }
            val request = Request.Builder().url(part.url).header("User-Agent",ua).header("Referer",referer)
                .header("Accept-Encoding","identity")
            if(part.length > 0 || part.start > 0) {
                val end = if(part.length > 0) Math.addExact(part.start,part.length-1).toString() else ""
                request.header("Range","bytes=${part.start}-$end")
            }
            val secret = part.keyUrl?.let { url ->
                require(allowed(url))
                keys.getOrPut(url) {
                    client.newCall(Request.Builder().url(url).header("User-Agent",ua).header("Referer",referer).build())
                        .also(track).execute().use { response ->
                            check(response.isSuccessful)
                            val bytes = response.body!!.byteStream().readNBytes(17)
                            check(bytes.size == 16) { "Invalid AES-128 key" }; bytes
                        }
                }
            }
            val temporary = File(directory,"$index.part")
            client.newCall(request.build()).also(track).execute().use { response ->
                context.ensureActive()
                if(response.code != 200 && response.code != 206) throw MediaHttpException(response.code)
                val body = response.body ?: error("Empty media fragment")
                var expected = body.contentLength()
                if(part.length > 0 || part.start > 0 || response.code == 206) {
                    val range = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matchEntire(response.header("Content-Range").orEmpty())
                    check(response.code == 206 && range != null) { "Missing fragment byte range" }
                    val start = range.groupValues[1].toLong(); val end = range.groupValues[2].toLong()
                    check(start == part.start && end >= start && (part.length < 0 || end-start+1 == part.length)) { "Wrong fragment byte range" }
                    check(expected < 0 || expected == end-start+1)
                    expected = end-start+1
                }
                var received = 0L
                temporary.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buffer=ByteArray(128*1024)
                        while(true) {
                            context.ensureActive()
                            val count=input.read(buffer); if(count < 0) break
                            out.write(buffer,0,count); received+=count; written+=count; report()
                        }
                    }
                }
                check(received > 0 && (expected < 0 || received == expected)) { "Incomplete media fragment" }
            }
            context.ensureActive()
            if(secret != null) {
                val iv = requireNotNull(part.iv).removePrefix("0x").removePrefix("0X")
                require(iv.length <= 32 && iv.matches(Regex("[0-9a-fA-F]+")))
                val vector=BigInteger(iv,16).toByteArray().takeLast(16).toByteArray()
                val padded=ByteArray(16); vector.copyInto(padded,16-vector.size)
                val cipher=Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(secret,"AES"),IvParameterSpec(padded))
                val decrypted=File(directory,"$index.clear")
                CipherInputStream(temporary.inputStream(),cipher).use { input ->
                    decrypted.outputStream().use { out ->
                        val buffer=ByteArray(128*1024)
                        while(true) { context.ensureActive(); val count=input.read(buffer); if(count<0) break; out.write(buffer,0,count) }
                    }
                }
                check(decrypted.length()>0 && decrypted.renameTo(complete))
                temporary.delete()
            } else check(temporary.renameTo(complete))
            report(true)
        }
        output.outputStream().use { out ->
            for(index in plan.segments.indices) File(directory,"$index.complete").inputStream().use { input ->
                val buffer=ByteArray(128*1024)
                while(true) { context.ensureActive(); val count=input.read(buffer); if(count<0) break; out.write(buffer,0,count) }
            }
        }
        context.ensureActive()
        progress(output.length(),output.length(),0)
    }
}
