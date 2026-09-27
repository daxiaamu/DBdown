package com.daxiaamu.dbdown.update

import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.time.Instant

data class UpdateManifest(
    val channel: String, val versionCode: Long, val versionName: String,
    val publishedAt: Instant?, val changelog: String, val maxForcedVersionCode: Long,
    val revision: Long, val urls: List<String>, val sha256: String, val size: Long, val raw: String
) {
    fun required(current: Long) = current <= maxForcedVersionCode && versionCode > current
    val identity get() = "$versionCode-$sha256"
}
data class UpdatePointer(val channel: String, val revision: Long, val manifestPath: String,
                         val sha256: String, val expiresAt: Instant, val raw: String)
data class AcceptedUpdate(val pointer: UpdatePointer, val manifest: UpdateManifest)

object UpdateProtocol {
    const val MAX_METADATA = 1024 * 1024
    const val MAX_APK = 512L * 1024 * 1024
    private val hash = Regex("[0-9a-f]{64}")
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
    fun https(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.fragment == null
    }.getOrDefault(false)
    private fun number(o: JSONObject, name: String): Long {
        val n = o.get(name)
        require(n is Int || n is Long) { "$name 必须为整数" }
        return (n as Number).toLong()
    }
    fun pointer(raw: String, channel: String, now: Instant = Instant.now(), checkExpiry: Boolean = true): UpdatePointer {
        require(raw.toByteArray().size <= MAX_METADATA)
        val o = JSONObject(raw)
        require(number(o, "schemaVersion") == 1L && o.getString("channel") == channel)
        val revision = number(o, "policyRevision")
        val digest = o.getString("manifestSha256")
        val path = o.getString("manifestPath")
        require(revision > 0 && hash.matches(digest))
        require(path == "updates/$channel/manifests/$revision-$digest.json") { "更新清单路径无效" }
        val expires = Instant.parse(o.getString("expiresAt"))
        if(checkExpiry) require(expires.isAfter(now)) { "更新指针已过期，请稍后重试" }
        return UpdatePointer(channel, revision, path, digest, expires, raw)
    }
    fun manifest(raw: String, pointer: UpdatePointer): UpdateManifest {
        require(raw.toByteArray().size <= MAX_METADATA)
        require(sha256(raw.toByteArray()) == pointer.sha256) { "更新清单摘要不一致" }
        val o = JSONObject(raw)
        require(number(o, "schemaVersion") == 1L && o.getString("channel") == pointer.channel)
        val code = number(o, "versionCode")
        val revision = number(o, "policyRevision")
        val forced = number(o, "maxForcedVersionCode")
        require(code > 0 && revision == pointer.revision && forced >= 0 && forced < code)
        val name = o.getString("versionName")
        require(name.isNotBlank() && name.length <= 80)
        val digest = o.getString("sha256")
        require(hash.matches(digest)) { "APK SHA-256 无效" }
        val size = number(o, "size")
        require(size in 1..MAX_APK)
        val array = o.getJSONArray("urls")
        require(array.length() in 5..20)
        val urls = (0 until array.length()).map { array.getString(it) }.distinct()
        require(urls.all(::https))
        val cdnHosts = urls.map { URI(it).host.lowercase() }.filter {
            it !in setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")
        }.distinct()
        require(cdnHosts.size >= 5) { "更新 APK 必须至少有 5 个不同主机的 CDN" }
        val published = runCatching { Instant.parse(o.optString("publishedAt")) }.getOrNull()
        val notes = o.optString("changelog", o.optString("notes"))
        require(notes.length <= 100_000)
        return UpdateManifest(pointer.channel, code, name, published, notes, forced, revision, urls, digest, size, raw)
    }
    fun validateAdvance(candidate: AcceptedUpdate, previous: AcceptedUpdate?) {
        if(previous == null) return
        require(candidate.pointer.revision >= previous.pointer.revision) { "拒绝过期的更新缓存" }
        if(candidate.pointer.revision == previous.pointer.revision)
            require(candidate.pointer.sha256 == previous.pointer.sha256) { "同一策略版本的清单发生冲突" }
        require(candidate.manifest.versionCode >= previous.manifest.versionCode) { "拒绝更新版本回退" }
        require(candidate.manifest.maxForcedVersionCode >= previous.manifest.maxForcedVersionCode) { "拒绝降低强制更新边界" }
    }
    fun rejectConflicts(pointers: List<UpdatePointer>) {
        require(pointers.groupBy { it.revision }.values.none { group -> group.map { it.sha256 }.distinct().size > 1 }) {
            "更新源出现发布冲突，请稍后重试"
        }
    }
}
