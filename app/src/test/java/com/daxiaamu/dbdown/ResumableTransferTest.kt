package com.daxiaamu.dbdown

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit

class ResumableTransferTest {
    @get:Rule val temp = TemporaryFolder()
    private val client = OkHttpClient()
    private fun partial(file: File, url: String, text: String = "abc") {
        file.writeText(text)
        Properties().apply {
            setProperty("url", url); setProperty("media", "video"); setProperty("validator", "\"v1\"")
        }.also { props -> File(file.path + ".resume").outputStream().use { props.store(it, null) } }
    }
    @Test fun validRangeAppendsAndReportsFullLength() = runBlocking {
        MockWebServer().use { server ->
            val url = server.url("/video").toString(); val file = temp.newFile()
            partial(file, url)
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 3-5/6")
                .setHeader("ETag", "\"v1\"").setBody("def"))
            var bytes = 0L; var total = 0L
            ResumableTransfer(client) {}.download(url, file, "video", "test", url) { b,t,_ -> bytes=b; total=t }
            assertEquals("abcdef", file.readText()); assertEquals(6L, bytes); assertEquals(6L, total)
            val request = server.takeRequest()
            assertEquals("bytes=3-", request.getHeader("Range")); assertEquals("\"v1\"", request.getHeader("If-Range"))
        }
    }
    @Test fun ignoredRangeReplacesInsteadOfCorruptingFile() = runBlocking {
        MockWebServer().use { server ->
            val url = server.url("/video").toString(); val file = temp.newFile()
            partial(file, url)
            server.enqueue(MockResponse().setHeader("ETag", "\"v2\"").setBody("new-content"))
            ResumableTransfer(client) {}.download(url, file, "video", "test", url) { _,_,_ -> }
            assertEquals("new-content", file.readText())
        }
    }
    @Test fun unsatisfiableRangeRetriesFullRequest() = runBlocking {
        MockWebServer().use { server ->
            val url = server.url("/video").toString(); val file = temp.newFile()
            partial(file, url)
            server.enqueue(MockResponse().setResponseCode(416))
            server.enqueue(MockResponse().setBody("replacement"))
            ResumableTransfer(client) {}.download(url, file, "video", "test", url) { _,_,_ -> }
            assertEquals("replacement", file.readText())
            assertNotNull(server.takeRequest().getHeader("Range")); assertNull(server.takeRequest().getHeader("Range"))
        }
    }
    @Test fun invalidRangeNeverAppends() = runBlocking {
        MockWebServer().use { server ->
            val url = server.url("/video").toString(); val file = temp.newFile()
            partial(file, url)
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 2-4/5").setBody("def"))
            val failure = runCatching { ResumableTransfer(client) {}.download(url, file, "video", "test", url) { _,_,_ -> } }
            assertTrue(failure.isFailure); assertEquals("abc", file.readText())
        }
    }
    @Test fun cancelledTransferKeepsBytesAndResumesExactly() = runBlocking {
        MockWebServer().use { server ->
            val url = server.url("/video").toString(); val file = temp.newFile()
            val data = ByteArray(128 * 1024) { (it % 251).toByte() }
            server.enqueue(MockResponse().setHeader("ETag", "\"v1\"").setBody(Buffer().write(data))
                .throttleBody(4096, 30, TimeUnit.MILLISECONDS))
            val received = CompletableDeferred<Unit>()
            var call: okhttp3.Call? = null
            val job = launch(Dispatchers.IO) {
                try { ResumableTransfer(client) { call = it }.download(url, file, "video", "test", url) { b,_,_ ->
                    if(b > 0) received.complete(Unit)
                } } catch(e: Exception) { currentCoroutineContext().ensureActive(); throw e }
            }
            withTimeout(5000) { received.await() }
            job.cancel(); call?.cancel(); job.join()
            val size = file.length().toInt()
            assertTrue(size in 1 until data.size)
            server.enqueue(MockResponse().setResponseCode(206).setHeader("ETag", "\"v1\"")
                .setHeader("Content-Range", "bytes $size-${data.size-1}/${data.size}")
                .setBody(Buffer().write(data, size, data.size-size)))
            ResumableTransfer(client) {}.download(url, file, "video", "test", url) { _,_,_ -> }
            assertArrayEquals(data, file.readBytes())
        }
    }
}
