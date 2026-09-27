package com.daxiaamu.dbdown.update

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal class UpdateApk(private val context: Context,
    private val client: okhttp3.OkHttpClient = UpdateSource.client.newBuilder().readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES).build()
) {
    private val directory get() = File(context.cacheDir, "updates").apply { mkdirs() }
    fun file(manifest: UpdateManifest) = File(directory, "${manifest.identity}.apk")

    suspend fun verify(manifest: UpdateManifest, file: File = file(manifest)) = withContext(Dispatchers.IO) {
        require(file.isFile && file.length() == manifest.size) { "更新文件大小不符" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while(true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer)
                if(n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val expected = manifest.sha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        require(MessageDigest.isEqual(expected, digest.digest())) { "更新文件 SHA-256 校验失败" }
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: error("无法读取更新 APK")
        require(archive.packageName == context.packageName) { "更新 APK 包名不符" }
        require(archive.longVersionCode == manifest.versionCode) { "更新 APK 版本不符" }
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val current = installed.signingInfo?.apkContentsSigners?.map { UpdateProtocol.sha256(it.toByteArray()) }?.toSet()
        val incoming = archive.signingInfo?.apkContentsSigners?.map { UpdateProtocol.sha256(it.toByteArray()) }?.toSet()
        require(!current.isNullOrEmpty() && current == incoming) { "更新签名与已安装应用不一致" }
    }
    suspend fun download(manifest: UpdateManifest, progress: (Float?) -> Unit): File = withContext(Dispatchers.IO) {
        val destination = file(manifest)
        val temp = File(directory, "${manifest.identity}.download")
        var lastError: Exception? = null
        for(url in manifest.urls) {
            currentCoroutineContext().ensureActive()
            temp.delete()
            try {
                requestHttps(client, url).use { response ->
                    check(response.isSuccessful) { "下载源返回 ${response.code}" }
                    val body = response.body ?: error("下载内容为空")
                    check(body.contentType()?.subtype != "html") { "下载源返回错误页面" }
                    val length = body.contentLength()
                    require(length < 0 || length == manifest.size) { "下载源文件大小不符" }
                    val hash = MessageDigest.getInstance("SHA-256")
                    var received = 0L
                    var last = 0L
                    progress(if(length > 0) 0f else null)
                    FileOutputStream(temp).use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(128 * 1024)
                            while(true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buffer)
                                if(n < 0) break
                                received += n
                                require(received <= manifest.size && received <= UpdateProtocol.MAX_APK) { "更新文件超出预期大小" }
                                output.write(buffer, 0, n); hash.update(buffer, 0, n)
                                val now = System.nanoTime()
                                if(now-last > 300_000_000) {
                                    progress(if(length > 0) (received.toFloat()/length).coerceAtMost(.99f) else null)
                                    last = now
                                }
                            }
                        }
                        output.fd.sync()
                    }
                    require(received == manifest.size) { "更新下载不完整" }
                    val actual = hash.digest().joinToString("") { "%02x".format(it) }
                    require(actual == manifest.sha256) { "下载源 SHA-256 不符" }
                }
                verify(manifest, temp)
                Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                progress(1f)
                return@withContext destination
            } catch(e: CancellationException) { temp.delete(); throw e }
            catch(e: Exception) { lastError = e; temp.delete() }
        }
        throw lastError ?: IllegalStateException("所有更新下载源均不可用")
    }
}
