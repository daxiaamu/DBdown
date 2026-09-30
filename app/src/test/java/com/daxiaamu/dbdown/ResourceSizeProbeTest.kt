package com.daxiaamu.dbdown

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class ResourceSizeProbeTest {
    @Test fun readsFullSizeWithResourceUserAgent()=runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Length",12345))
            val resource=DownloadResource("audio",server.url("/audio").toString(),"audio-client")
            assertEquals(12345,probeResourceSize(OkHttpClient(),resource,"https://example.test/") {})
            val request=server.takeRequest()
            assertEquals("HEAD",request.method); assertEquals("audio-client",request.getHeader("User-Agent"))
        }
    }
    @Test fun refusesPartialOrFailedOrEncodedSizes()=runBlocking {
        MockWebServer().use { server ->
            for(response in listOf(MockResponse().setResponseCode(403).setHeader("Content-Length",50),
                MockResponse().setResponseCode(206).setHeader("Content-Length",1),
                MockResponse().setHeader("Content-Encoding","gzip").setHeader("Content-Length",40))) {
                server.enqueue(response)
                assertEquals(-1,probeResourceSize(OkHttpClient(),DownloadResource("v",server.url("/").toString(),"ua"),"") {})
            }
        }
    }
    @Test fun cancelledProbeCancelsNetworkCall()=runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeadersDelay(2,TimeUnit.SECONDS))
            var call: okhttp3.Call?=null
            val job=launch { probeResourceSize(OkHttpClient(),DownloadResource("v",server.url("/").toString(),"ua"),"") { call=it } }
            yield(); withContext(Dispatchers.IO) { server.takeRequest(2,TimeUnit.SECONDS) }
            job.cancelAndJoin()
            assertTrue(call!!.isCanceled())
        }
    }
}
