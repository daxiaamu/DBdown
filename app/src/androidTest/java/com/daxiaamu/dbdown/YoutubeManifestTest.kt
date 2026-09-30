package com.daxiaamu.dbdown

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class YoutubeManifestTest {
    private val root="https://test.googlevideo.com/"
    private fun parser(files: Map<String,String>)=YoutubeManifests({ url,_ -> files.getValue(url.removePrefix(root)).toByteArray() })
    @Test fun dashTemplateEnumeratesInitializationAndEverySegment() {
        val mpd="""<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" mediaPresentationDuration="PT6S" type="static" minBufferTime="PT1S"><Period><AdaptationSet mimeType="video/mp4" contentType="video"><Representation id="high" bandwidth="12000000" width="7680" height="4320" codecs="av01.0.16M.10" frameRate="60"><SegmentTemplate timescale="1" duration="2" startNumber="1" initialization="init.mp4" media="part-${'$'}Number${'$'}.m4s"/></Representation></AdaptationSet></Period></MPD>"""
        val tracks=parser(mapOf("manifest.mpd" to mpd)).dash(root+"manifest.mpd","ua","test")
        assertEquals(4320,tracks.single().height)
        assertEquals(listOf("init.mp4","part-1.m4s","part-2.m4s","part-3.m4s"),tracks.single().plan!!.segments.map { it.url.removePrefix(root) })
    }
    @Test fun dashSegmentBaseDownloadsWholeFileWithoutDuplicatingInitialization() {
        val mpd="""<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" mediaPresentationDuration="PT6S" type="static" minBufferTime="PT1S"><Period><AdaptationSet mimeType="video/mp4" contentType="video"><Representation id="high" bandwidth="12000000" width="3840" height="2160" codecs="avc1.640033"><BaseURL>whole.mp4</BaseURL><SegmentBase><Initialization range="0-999"/></SegmentBase></Representation></AdaptationSet></Period></MPD>"""
        val track=parser(mapOf("manifest.mpd" to mpd)).dash(root+"manifest.mpd","ua","test").single()
        assertNull(track.plan); assertEquals(root+"whole.mp4",track.url)
    }
    @Test fun hlsMasterPreservesHdrAndSeparateAudioAndByteRanges() {
        val master="""#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="sound",NAME="Original",DEFAULT=YES,AUTOSELECT=YES,LANGUAGE="en",URI="audio.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=12000000,RESOLUTION=3840x2160,FRAME-RATE=60,CODECS="hvc1.2.4.L153.B0,mp4a.40.2",VIDEO-RANGE=PQ,AUDIO="sound"
video.m3u8
"""
        val media="""#EXTM3U
#EXT-X-TARGETDURATION:2
#EXT-X-MAP:URI="combined.mp4",BYTERANGE="20@0"
#EXTINF:2,
#EXT-X-BYTERANGE:30@20
combined.mp4
#EXTINF:2,
#EXT-X-BYTERANGE:40
combined.mp4
#EXT-X-ENDLIST
"""
        val parser=parser(mapOf("master.m3u8" to master,"video.m3u8" to media))
        val tracks=parser.hls(root+"master.m3u8","ua","test")
        assertEquals(2,tracks.size); assertTrue(tracks.first().hdr); assertFalse(tracks.first().audio)
        assertTrue(tracks.last().defaultAudio)
        val plan=parser.prepare(tracks.first()).plan!!
        assertEquals(listOf(0L,20L,50L),plan.segments.map { it.start })
        assertEquals(listOf(20L,30L,40L),plan.segments.map { it.length })
    }
    @Test fun rejectsLiveAndDiscontinuousOrDrmPlaylists() {
        val base="#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\none.ts\n"
        for(media in listOf(base,base+"#EXT-X-DISCONTINUITY\n#EXTINF:2,\ntwo.ts\n#EXT-X-ENDLIST\n",
            "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://key\",KEYFORMAT=\"com.apple.streamingkeydelivery\"\n#EXTINF:2,\none.ts\n#EXT-X-ENDLIST\n")) {
            assertTrue(runCatching { parser(mapOf("v.m3u8" to media)).hls(root+"v.m3u8","ua","test") }.isFailure)
        }
    }
    @Test fun finiteDashDownloadsVideoAndAudioWithoutLosingDuration() = runBlocking {
        val dir=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"dash-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            val generated=FFmpegKit.executeWithArguments(arrayOf("-y","-v","error","-f","lavfi","-i","color=c=blue:s=160x90:r=10",
                "-f","lavfi","-i","sine=frequency=1000:sample_rate=48000","-t","3","-c:v","mpeg4","-g","10","-c:a","aac",
                "-f","dash","-seg_duration","1",File(dir,"test.mpd").absolutePath))
            assertTrue(generated.output,ReturnCode.isSuccess(generated.returnCode))
            MockWebServer().use { server ->
                server.dispatcher=object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val file=File(dir,request.path!!.removePrefix("/"))
                        return if(file.isFile) MockResponse().setBody(Buffer().write(file.readBytes())) else MockResponse().setResponseCode(404)
                    }
                }
                val url=server.url("/test.mpd").toString(); val client=OkHttpClient()
                val allowed: (String)->Boolean = { it.startsWith(server.url("/").toString()) }
                val parser=YoutubeManifests({ target,_ -> client.newCall(okhttp3.Request.Builder().url(target).build()).execute().use { it.body!!.bytes() } },allowed)
                val tracks=parser.dash(url,"test","fixture")
                val video=tracks.single { it.video }; val audio=tracks.single { it.audio }
                val v=File(dir,"v.mp4"); val a=File(dir,"a.m4a"); val output=File(dir,"output.mp4")
                val transfer=SegmentTransfer(client,{},allowed)
                transfer.download(video.plan!!,v,"test",url) { _,_,_ -> }
                transfer.download(audio.plan!!,a,"test",url) { _,_,_ -> }
                LosslessMuxer.merge(v,a,output,"aac")
                val probe=FFprobeKit.executeWithArguments(arrayOf("-v","error","-show_streams","-show_format","-of","json",output.absolutePath))
                assertTrue(ReturnCode.isSuccess(probe.returnCode)); val json=JSONObject(probe.output)
                assertTrue(json.getJSONObject("format").getDouble("duration")>=2.9)
                assertEquals(2,json.getJSONArray("streams").length())
            }
        } finally { dir.deleteRecursively() }
    }
    @Test fun finiteHlsDownloadsAllFragmentsAndRemuxesFullDuration() = runBlocking {
        val dir=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"hls-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            val generated=FFmpegKit.executeWithArguments(arrayOf("-y","-v","error","-f","lavfi","-i","color=c=blue:s=160x90:r=10",
                "-f","lavfi","-i","sine=frequency=1000:sample_rate=48000","-t","3","-c:v","mpeg2video","-g","10","-c:a","aac",
                "-f","hls","-hls_time","1","-hls_list_size","0","-hls_segment_filename",File(dir,"part%d.ts").absolutePath,File(dir,"test.m3u8").absolutePath))
            assertTrue(generated.output,ReturnCode.isSuccess(generated.returnCode))
            MockWebServer().use { server ->
                server.dispatcher=object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val file=File(dir,request.path!!.removePrefix("/"))
                        return if(file.isFile) MockResponse().setBody(Buffer().write(file.readBytes())) else MockResponse().setResponseCode(404)
                    }
                }
                val url=server.url("/test.m3u8").toString(); val client=OkHttpClient()
                val parser=YoutubeManifests({ target,_ -> client.newCall(okhttp3.Request.Builder().url(target).build()).execute().use { it.body!!.bytes() } },{ it.startsWith(server.url("/").toString()) })
                val stream=parser.hls(url,"test","fixture").single(); assertTrue(stream.plan!!.segments.size>=3)
                val input=File(dir,"input.ts"); val output=File(dir,"output.mp4")
                SegmentTransfer(client,{}, { it.startsWith(server.url("/").toString()) }).download(stream.plan,input,"test",url) { _,_,_ -> }
                LosslessMuxer.remux(input,output)
                val probe=FFprobeKit.executeWithArguments(arrayOf("-v","error","-show_streams","-show_format","-of","json",output.absolutePath))
                assertTrue(ReturnCode.isSuccess(probe.returnCode)); val json=JSONObject(probe.output)
                assertTrue(json.getJSONObject("format").getDouble("duration")>=2.9)
                val streams=json.getJSONArray("streams"); assertEquals(2,streams.length())
                assertEquals("mpeg2video",streams.getJSONObject(0).getString("codec_name"))
                assertEquals("aac",streams.getJSONObject(1).getString("codec_name"))
            }
        } finally { dir.deleteRecursively() }
    }
}
