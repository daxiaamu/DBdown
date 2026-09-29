package com.daxiaamu.dbdown.update

import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

data class MetadataSource(val url: String, val family: String)

class UpdateSource internal constructor(private val repository: String, private val branch: String,
    private val http: OkHttpClient = client,
    private val sourceOverride: ((String) -> List<MetadataSource>)? = null
) {
    companion object {
        val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS).callTimeout(18, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()
    }
    init {
        require(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(repository)) { "尚未配置更新仓库" }
        require(Regex("[A-Za-z0-9_.-]+").matches(branch))
    }
    fun sources(path: String): List<MetadataSource> {
        val endpoints = sourceOverride?.invoke(path) ?: listOf(
        MetadataSource("https://api.github.com/repos/$repository/contents/$path?ref=$branch", "authority"),
        MetadataSource("https://raw.githubusercontent.com/$repository/$branch/$path", "github"),
        MetadataSource("https://cdn.jsdelivr.net/gh/$repository@$branch/$path", "jsdelivr"),
        MetadataSource("https://fastly.jsdelivr.net/gh/$repository@$branch/$path", "jsdelivr"),
        MetadataSource("https://gcore.jsdelivr.net/gh/$repository@$branch/$path", "jsdelivr"),
        MetadataSource("https://testingcf.jsdelivr.net/gh/$repository@$branch/$path", "jsdelivr"),
        MetadataSource("https://cdn.statically.io/gh/$repository/$branch/$path", "statically")
        )
        require(endpoints.size >= 5 && endpoints.map { it.url }.distinct().size >= 5)
        require(endpoints.all { UpdateProtocol.https(it.url) })
        require(endpoints.first().family == "authority" && endpoints.count { it.family == "authority" } == 1)
        return endpoints
    }
    suspend fun fetch(channel: String, previous: AcceptedUpdate?): AcceptedUpdate = supervisorScope {
        val endpoints = sources("updates/$channel/latest.json")
        val jobs = endpoints.map { source -> async {
            try { UpdateProtocol.pointer(read(source.url), channel) } catch(e: CancellationException) { throw e }
            catch(_: Exception) { null }
        } }
        try {
            val apiAuthority = jobs.first().await()
            // GitHub Raw is another HTTPS endpoint of the same repository authority,
            // not an unauthenticated third-party mirror. API outages must not disable it.
            val rawAuthority = if(apiAuthority == null) endpoints.indexOfFirst { it.family == "github" }
                .takeIf { it >= 0 }?.let { jobs[it].await() } else null
            val authority = apiAuthority ?: rawAuthority
            if(authority != null) delay(600) else jobs.awaitAll()
            val replies = jobs.mapIndexedNotNull { index, job ->
                if(job.isCompleted && !job.isCancelled) job.await()?.let { endpoints[index] to it } else null
            }
            UpdateProtocol.rejectConflicts(replies.map { it.second })
            val pointer = authority ?: run {
                // Unsigned mirrors can only reuse a previously authenticated authority pointer.
                // All jsDelivr ingress hosts count as one provider, not independent votes.
                val eligible = replies.filter { (_, p) -> previous != null &&
                    p.revision == previous.pointer.revision && p.sha256 == previous.pointer.sha256 }
                require(eligible.map { it.first.family }.distinct().size >= 2) { "检查更新失败，请稍后重试" }
                eligible.first().second
            }
            if(previous != null) {
                require(pointer.revision >= previous.pointer.revision) { "拒绝过期更新缓存" }
                if(pointer.revision == previous.pointer.revision)
                    require(pointer.sha256 == previous.pointer.sha256) { "更新清单冲突" }
            }
            var manifest: UpdateManifest? = null
            for(source in sources(pointer.manifestPath)) {
                currentCoroutineContext().ensureActive()
                try { manifest = UpdateProtocol.manifest(read(source.url), pointer); break }
                catch(e: CancellationException) { throw e }
                catch(_: Exception) { }
            }
            val accepted = AcceptedUpdate(pointer, manifest ?: error("无法获取经过校验的更新清单"))
            UpdateProtocol.validateAdvance(accepted, previous)
            accepted
        } finally { jobs.forEach { it.cancel() } }
    }
    /** Repository content shares the updater's endpoints, bounded reads and HTTPS handling. */
    internal suspend fun <T : Any> repositoryJson(path: String, parse: (String) -> T): T = supervisorScope {
        require(Regex("[A-Za-z0-9_./-]+").matches(path) && ".." !in path)
        val jobs = sources(path).map { source -> async {
            try { parse(read(source.url)) }
            catch(e: CancellationException) { throw e }
            catch(_: Exception) { null }
        } }
        try {
            // Prefer repository authority, then fall back in the same CDN order as updates.
            jobs.firstNotNullOfOrNull { it.await() } ?: error("无法读取仓库配置")
        } finally { jobs.forEach { it.cancel() } }
    }

    private suspend fun read(url: String): String {
        val separator = if('?' in url) "&" else "?"
        return withContext(Dispatchers.IO) {
            requestHttps(http, "$url${separator}_=${System.currentTimeMillis()}", "application/vnd.github.raw+json").use {
                check(it.isSuccessful) { "更新源返回 ${it.code}" }
                val body = it.body ?: error("更新内容为空")
                require(body.contentLength() <= UpdateProtocol.MAX_METADATA) { "更新清单过大" }
                val output = ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    while(true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if(n < 0) break
                        require(output.size() + n <= UpdateProtocol.MAX_METADATA) { "更新清单过大" }
                        output.write(buffer, 0, n)
                    }
                }
                val raw = output.toString(Charsets.UTF_8.name())
                val json = JSONObject(raw)
                if(json.optString("encoding") == "base64")
                    String(Base64.getMimeDecoder().decode(json.getString("content")), Charsets.UTF_8)
                else raw
            }
        }
    }
}
internal suspend fun requestHttps(client: OkHttpClient, initial: String, accept: String = "*/*"): Response {
    var url = initial
    repeat(6) {
        require(UpdateProtocol.https(url)) { "更新地址必须使用 HTTPS" }
        val call = client.newCall(Request.Builder().url(url).header("Accept", accept)
            .header("Cache-Control", "no-cache").header("User-Agent", "DBDown-Updater/1").build())
        val response = suspendCancellableCoroutine<Response> { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if(continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { _, value, _ -> value.close() }
                }
            })
        }
        if(response.code in 300..399) {
            val next = response.header("Location")?.let { response.request.url.resolve(it)?.toString() }
            response.close()
            url = next ?: error("更新源重定向无效")
        } else return response
    }
    error("更新源重定向次数过多")
}
