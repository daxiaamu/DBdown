package com.daxiaamu.dbdown

import com.daxiaamu.dbdown.update.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdateSourceTest {
    private fun fixture(revision: Int = 1, code: Int = 10): AcceptedUpdate {
        val raw = JSONObject().put("schemaVersion", 1).put("channel", "stable").put("versionCode", code)
            .put("versionName", "v$code").put("policyRevision", revision).put("maxForcedVersionCode", 0)
            .put("size", 100).put("sha256", "a".repeat(64))
            .put("urls", JSONArray((1..5).map { "https://cdn$it.example/a.apk" })).toString()
        val hash = UpdateProtocol.sha256(raw.toByteArray())
        val pointer = UpdateProtocol.pointer(JSONObject().put("schemaVersion", 1).put("channel", "stable")
            .put("policyRevision", revision).put("manifestSha256", hash)
            .put("manifestPath", "updates/stable/manifests/$revision-$hash.json")
            .put("expiresAt", "2099-01-01T00:00:00Z").toString(), "stable")
        return AcceptedUpdate(pointer, UpdateProtocol.manifest(raw, pointer))
    }
    private fun withSource(replies: List<AcceptedUpdate?>, block: (UpdateSource) -> Unit) {
        MockWebServer().use { server ->
            server.useHttps(TestTls.socketFactory, false)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    val index = path.split('/')[1].toInt()
                    val reply = replies[index] ?: return MockResponse().setResponseCode(503)
                    return MockResponse().setBody(if(path.endsWith("latest.json")) reply.pointer.raw else reply.manifest.raw)
                }
            }
            val client = UpdateSource.client.newBuilder().sslSocketFactory(TestTls.socketFactory, TestTls.trustManager).build()
            val source = UpdateSource("daxiaamu/DBdown", "main", client) { path ->
                (0..4).map { i -> MetadataSource(server.url("/$i/$path").toString(),
                    listOf("authority", "github", "jsdelivr", "jsdelivr", "statically")[i]) }
            }
            block(source)
        }
    }
    @Test fun authorityBeatsStaleMirrorsAndManifestIsHashBound() = runBlocking {
        val new = fixture(2, 11); val old = fixture()
        withSource(listOf(new, old, old, old, old)) { source ->
            runBlocking { assertEquals(11, source.fetch("stable", old).manifest.versionCode.toInt()) }
        }
    }
    @Test fun sameRevisionConflictRejectsAuthorityToo() = runBlocking {
        withSource(listOf(fixture(1, 11), fixture(), null, null, null)) { source ->
            assertTrue(runCatching { runBlocking { source.fetch("stable", null) } }.isFailure)
        }
    }
    @Test fun cachedAuthorityCanBeRecoveredFromIndependentProvidersOnly() = runBlocking {
        val cached = fixture()
        withSource(listOf(null, cached, cached, null, null)) { source ->
            runBlocking { assertEquals(cached.pointer.sha256, source.fetch("stable", cached).pointer.sha256) }
        }
        withSource(listOf(null, null, cached, cached, null)) { source ->
            assertTrue(runCatching { runBlocking { source.fetch("stable", cached) } }.isFailure)
        }
    }
    @Test fun officialRawCanIntroduceNewVersionWhenApiIsUnavailable() = runBlocking {
        val new = fixture(3, 15)
        withSource(listOf(null, new, fixture(), fixture(), null)) { source ->
            runBlocking {
                assertEquals(15, source.fetch("stable", fixture(2, 10)).manifest.versionCode.toInt())
                assertEquals(15, source.fetch("stable", null).manifest.versionCode.toInt())
            }
        }
    }
    @Test fun officialRawFallbackStillRejectsRollbackAndConflicts() = runBlocking {
        withSource(listOf(null, fixture(), null, null, null)) { source ->
            assertTrue(runCatching { runBlocking { source.fetch("stable", fixture(2, 11)) } }.isFailure)
        }
        withSource(listOf(null, fixture(2, 11), fixture(2, 12), null, null)) { source ->
            assertTrue(runCatching { runBlocking { source.fetch("stable", fixture()) } }.isFailure)
        }
    }
    @Test fun unsignedMirrorsCannotIntroduceUnauthenticatedNewRevision() = runBlocking {
        val new = fixture(2, 11)
        withSource(listOf(null, null, new, null, new)) { source ->
            assertTrue(runCatching { runBlocking { source.fetch("stable", fixture()) } }.isFailure)
        }
    }
}
