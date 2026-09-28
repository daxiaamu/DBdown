package com.daxiaamu.dbdown

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.daxiaamu.dbdown.update.*
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveUpdateSourceTest {
    private fun report(text: String) = InstrumentationRegistry.getInstrumentation().sendStatus(0,
        android.os.Bundle().apply { putString("stream", "$text\n") })
    @Test fun diagnoseLiveSources() = runBlocking<Unit> {
        val source = UpdateSource("daxiaamu/DBdown", "main")
        try { report("FETCH_OK version=${source.fetch("stable", null).manifest.versionName}") }
        catch(e: Exception) { report("FETCH_FAILED ${e.javaClass.simpleName}: ${e.message}") }
        val apiUnavailable = UpdateSource("daxiaamu/DBdown", "main", sourceOverride = { path ->
            source.sources(path).map { endpoint ->
                if(endpoint.family == "authority") endpoint.copy(url = "https://api.github.com/repos/daxiaamu/DBdown/contents/not-present-probe.json") else endpoint
            }
        })
        val fallback = apiUnavailable.fetch("stable", null)
        org.junit.Assert.assertTrue(fallback.manifest.versionCode >= 15)
        report("API_UNAVAILABLE_RAW_FALLBACK_OK version=${fallback.manifest.versionName}")
        coroutineScope {
            source.sources("updates/stable/latest.json").map { endpoint -> async(Dispatchers.IO) {
                val host = java.net.URI(endpoint.url).host
                try {
                    val separator = if('?' in endpoint.url) "&" else "?"
                    requestHttps(UpdateSource.client, "${endpoint.url}${separator}_=${System.currentTimeMillis()}", "application/vnd.github.raw+json").use { response ->
                        val raw = response.body?.string().orEmpty()
                        val parsed = runCatching { UpdateProtocol.pointer(raw, "stable").revision }.fold({ "revision=$it" }, { "parse=${it.message}" })
                        report("SOURCE $host HTTP=${response.code} $parsed")
                    }
                } catch(e: Exception) { report("SOURCE $host ${e.javaClass.simpleName}: ${e.message}") }
            } }.awaitAll()
        }
    }
}
