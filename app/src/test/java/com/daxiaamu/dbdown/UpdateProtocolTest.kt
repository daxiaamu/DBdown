package com.daxiaamu.dbdown

import com.daxiaamu.dbdown.update.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class UpdateProtocolTest {
    private fun json(channel: String = "stable", revision: Long = 1, version: Long = 10, forced: Long = 0) = JSONObject()
        .put("schemaVersion", 1).put("channel", channel).put("policyRevision", revision)
        .put("versionCode", version).put("versionName", "0.6.1").put("maxForcedVersionCode", forced)
        .put("publishedAt", "2026-08-12T08:00:00Z").put("sha256", "a".repeat(64)).put("size", 1234)
        .put("changelog", "## 修复\n- 更新")
        .put("urls", JSONArray((1..5).map { "https://cdn$it.example/app.apk" }))
    private fun pair(o: JSONObject): AcceptedUpdate {
        val raw = o.toString()
        val digest = UpdateProtocol.sha256(raw.toByteArray())
        val channel = o.getString("channel")
        val revision = o.getLong("policyRevision")
        val pointer = UpdateProtocol.pointer(JSONObject().put("schemaVersion", 1).put("channel", channel)
            .put("policyRevision", revision).put("manifestSha256", digest)
            .put("manifestPath", "updates/$channel/manifests/$revision-$digest.json")
            .put("expiresAt", "2099-01-01T00:00:00Z").toString(), channel)
        return AcceptedUpdate(pointer, UpdateProtocol.manifest(raw, pointer))
    }
    @Test fun forcedBoundaryUsesCodeAndTimeIsAbsolute() {
        val manifest = pair(json(forced = 9)).manifest
        assertTrue(manifest.required(9)); assertFalse(manifest.required(10))
        assertEquals(Instant.parse("2026-08-12T08:00:00Z"), manifest.publishedAt)
        assertNull(pair(json().put("publishedAt", "2026-08-12 16:00")).manifest.publishedAt)
    }
    @Test fun rejectsMissingHashHttpTooFewCdnsAndNonIntegerCodes() {
        val cases = listOf(
            json().put("sha256", ""), json().put("sha256", "A".repeat(64)),
            json().put("versionCode", "10"), json().put("maxForcedVersionCode", 10),
            json().put("urls", JSONArray(listOf("https://github.com/o/r/a.apk"))),
            json().put("urls", JSONArray((1..5).map { "https://one.example/app.apk?q=$it" })),
            json().put("urls", JSONArray((1..5).map { "http://cdn$it.example/app.apk" }))
        )
        cases.forEach { assertTrue(runCatching { pair(it) }.isFailure) }
    }
    @Test fun rejectsRollbackConflictsAndForcedPolicyReduction() {
        val prior = pair(json(revision = 5, version = 20, forced = 10))
        assertTrue(runCatching { UpdateProtocol.validateAdvance(pair(json(revision = 4, version = 21, forced = 10)), prior) }.isFailure)
        assertTrue(runCatching { UpdateProtocol.validateAdvance(pair(json(revision = 6, version = 19, forced = 10)), prior) }.isFailure)
        assertTrue(runCatching { UpdateProtocol.validateAdvance(pair(json(revision = 6, version = 21, forced = 9)), prior) }.isFailure)
        assertTrue(runCatching { UpdateProtocol.validateAdvance(pair(json(revision = 5, version = 21, forced = 10)), prior) }.isFailure)
        assertTrue(runCatching { UpdateProtocol.rejectConflicts(listOf(prior.pointer, pair(json(revision = 5)).pointer)) }.isFailure)
        UpdateProtocol.validateAdvance(pair(json(revision = 6, version = 21, forced = 10)), prior)
    }
    @Test fun rejectsChangedManifestWrongChannelExpiredPointerAndInvalidPath() {
        val update = pair(json())
        assertTrue(runCatching { UpdateProtocol.manifest(json().put("versionCode", 11).toString(), update.pointer) }.isFailure)
        assertTrue(runCatching { UpdateProtocol.pointer(update.pointer.raw, "beta") }.isFailure)
        assertTrue(runCatching { UpdateProtocol.pointer(JSONObject(update.pointer.raw).put("expiresAt", "2000-01-01T00:00:00Z").toString(), "stable") }.isFailure)
        assertTrue(runCatching { UpdateProtocol.pointer(JSONObject(update.pointer.raw).put("manifestPath", "../../other").toString(), "stable") }.isFailure)
        assertFalse(UpdateProtocol.https("https://user:secret@host/path"))
    }
    @Test fun sourcesHaveOneAuthorityAndEnoughHttpsEndpoints() {
        val sources = UpdateSource("daxiaamu/DBdown", "main").sources("updates/stable/latest.json")
        assertTrue(sources.size >= 5)
        assertEquals(1, sources.count { it.family == "authority" })
        assertTrue(sources.all { UpdateProtocol.https(it.url) })
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun automaticAndManualShareInFlightButLaterManualAlwaysFetches() = runTest {
        var calls = 0
        val barrier = CompletableDeferred<Unit>()
        val results = mutableListOf<Pair<Int, Boolean>>()
        val check = SharedUpdateCheck(backgroundScope, {
            calls++; barrier.await(); calls
        }) { result, manual -> results.add(result.getOrThrow() to manual) }
        check.request(false); runCurrent()
        check.request(true); check.request(false); runCurrent()
        assertEquals(1, calls)
        barrier.complete(Unit); runCurrent()
        assertEquals(listOf(1 to true), results)
        check.request(true); runCurrent()
        assertEquals(listOf(1 to true, 2 to true), results)
    }
}
