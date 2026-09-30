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
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class SegmentTransferTest {
    @get:Rule val temp=TemporaryFolder()
    private fun transfer()=SegmentTransfer(OkHttpClient(),{}, { (it.startsWith("http://localhost:") || it.startsWith("http://127.0.0.1:")) && !it.endsWith("/forbidden") })
    @Test fun assemblesRangesAndReportsFinalBytes() = runBlocking {
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range","bytes 0-2/6").setBody("abc"))
            s.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range","bytes 3-5/6").setBody("def"))
            val url=s.url("/track").toString(); val file=temp.newFile(); var bytes=0L
            transfer().download(SegmentPlan(listOf(MediaSegment(url,0,3),MediaSegment(url,3,3)),"v"),file,"test",url) { b,_,_ -> bytes=b }
            assertEquals("abcdef",file.readText()); assertEquals(6L,bytes)
            assertEquals("bytes=0-2",s.takeRequest().getHeader("Range")); assertEquals("bytes=3-5",s.takeRequest().getHeader("Range"))
        }
    }
    @Test fun completedFragmentsSurviveFailureButChangedPlansDoNotReuseThem() = runBlocking {
        MockWebServer().use { s ->
            val one=s.url("/1").toString(); val two=s.url("/2").toString(); val file=temp.newFile()
            val plan=SegmentPlan(listOf(MediaSegment(one),MediaSegment(two)),"v")
            s.enqueue(MockResponse().setBody("first")); s.enqueue(MockResponse().setResponseCode(403))
            assertTrue(runCatching { transfer().download(plan,file,"test",one) { _,_,_ -> } }.isFailure)
            s.enqueue(MockResponse().setBody("second"))
            transfer().download(plan,file,"test",one) { _,_,_ -> }
            assertEquals("firstsecond",file.readText()); assertEquals(3,s.requestCount)
            s.enqueue(MockResponse().setBody("replacement"))
            transfer().download(SegmentPlan(listOf(MediaSegment(one)),"new"),file,"test",one) { _,_,_ -> }
            assertEquals("replacement",file.readText()); assertEquals(4,s.requestCount)
        }
    }
    @Test fun cancellationLeavesCompletedFragmentReusable() = runBlocking {
        MockWebServer().use { s ->
            val url=s.url("/1").toString(); val file=temp.newFile(); val plan=SegmentPlan(listOf(MediaSegment(url),MediaSegment(s.url("/2").toString())),"v")
            s.enqueue(MockResponse().setBody("one"))
            val worker=launch(Dispatchers.IO) {
                transfer().download(plan,file,"test",url) { bytes,_,_ -> if(bytes==3L) cancel() }
            }
            worker.join(); assertTrue(worker.isCancelled)
            s.enqueue(MockResponse().setBody("two"))
            transfer().download(plan,file,"test",url) { _,_,_ -> }
            assertEquals("onetwo",file.readText()); assertEquals(2,s.requestCount)
        }
    }
    @Test fun wrongByteRangeIsRejected() = runBlocking {
        MockWebServer().use { s ->
            val url=s.url("/1").toString(); s.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range","bytes 1-3/6").setBody("bad"))
            assertTrue(runCatching { transfer().download(SegmentPlan(listOf(MediaSegment(url,0,3)),"v"),temp.newFile(),"test",url) { _,_,_ -> } }.isFailure)
        }
    }
    @Test fun decryptsAes128BeforeJoiningFragments() = runBlocking {
        MockWebServer().use { s ->
            val key=ByteArray(16) { it.toByte() }; val iv=ByteArray(16).also { it[15]=1 }; val clear="original media packets".toByteArray()
            val cipher=Cipher.getInstance("AES/CBC/PKCS5Padding"); cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),IvParameterSpec(iv))
            s.enqueue(MockResponse().setBody(Buffer().write(key))); s.enqueue(MockResponse().setBody(Buffer().write(cipher.doFinal(clear))))
            val url=s.url("/media").toString(); val file=temp.newFile()
            transfer().download(SegmentPlan(listOf(MediaSegment(url,keyUrl=s.url("/key").toString(),iv="0x1")),"v"),file,"test",url) { _,_,_ -> }
            assertArrayEquals(clear,file.readBytes())
        }
    }
    @Test fun cannotRedirectSegmentsOutsideAllowedHosts() = runBlocking {
        MockWebServer().use { s ->
            val url=s.url("/media").toString(); s.enqueue(MockResponse().setResponseCode(302).setHeader("Location","http://127.0.0.1:${s.port}/forbidden"))
            assertTrue(runCatching { transfer().download(SegmentPlan(listOf(MediaSegment(url)),"v"),temp.newFile(),"test",url) { _,_,_ -> } }.isFailure)
            assertEquals(1,s.requestCount)
        }
    }
    @Test fun transientFragmentFailureRetriesWithoutDownloadingCompletedFragmentsAgain() = runBlocking {
        MockWebServer().use { s ->
            val one=s.url("/1").toString(); val two=s.url("/2").toString(); val file=temp.newFile()
            s.enqueue(MockResponse().setBody("first"))
            s.enqueue(MockResponse().setResponseCode(503))
            s.enqueue(MockResponse().setBody("second"))
            transfer().download(SegmentPlan(listOf(MediaSegment(one),MediaSegment(two)),"v"),file,"test",one) { _,_,_ -> }
            assertEquals("firstsecond",file.readText())
            assertEquals(listOf("/1","/2","/2"),List(3) { s.takeRequest().path })
        }
    }
}
