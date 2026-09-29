package com.daxiaamu.dbdown

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class VideoQualityTest {
    private val link = Links.detect("https://www.douyin.com/video/7687764891221937427")!!
    private fun page(video: String) = """<script>window._ROUTER_DATA={"loaderData":{"video_(id)/page":{"videoInfoRes":{"item_list":[{"aweme_id":"7687764891221937427","video":$video}]}}}};</script>"""
    @Test fun originalDimensionsDoNotDescribeShareStream() {
        val info = DouyinPage.parse(page("""{"width":3840,"height":2160,"play_addr":{"url_list":["https://aweme.snssdk.com/aweme/v1/playwm/?video_id=test&ratio=720p"]}}"""), link)
        assertTrue(info.video.contains("ratio=1080p"))
        assertTrue(info.video.startsWith("https://www.douyin.com/"))
        assertTrue(info.videoFallbacks.any { it.contains("ratio=720p") })
        assertEquals("", info.resolution)
    }
    @Test fun usesHigherReturnedStreamInsteadOf1080Ceiling() {
        val info = DouyinPage.parse(page("""{"play_addr":{"url_list":["https://cdn.example/default.mp4"]},"bit_rate":[{"bit_rate":4000,"play_addr":{"width":1920,"height":1080,"url_list":["https://cdn.example/1080.mp4"]}},{"bit_rate":8000,"play_addr":{"width":3840,"height":2160,"url_list":["https://cdn.example/4k.mp4"]}}]}"""),link)
        assertEquals("https://cdn.example/4k.mp4",info.video)
        assertEquals("https://cdn.example/1080.mp4",info.videoFallbacks.first())
    }
    @Test fun douyinKeepsOriginalMuxedAudioWithHighestBitrateVideo() {
        val info = DouyinPage.parse(page("""{"play_addr":{"url_list":["https://cdn.example/default.mp4"]},"bit_rate":[
            {"bit_rate":4000,"play_addr":{"width":1920,"height":1080,"url_list":["https://cdn.example/low.mp4"]}},
            {"bit_rate":8000,"play_addr":{"width":1920,"height":1080,"url_list":["https://cdn.example/high.mp4"]}}]}"""), link)
        assertEquals("https://cdn.example/high.mp4", info.video)
        assertNull(info.audio)
        assertNull(info.music)
    }
    @Test fun biliCanSelectHigherHevcInsteadOfLowerAvc() {
        val stream = bestBiliVideo(JSONArray("""[{"width":1920,"height":1080,"codecs":"avc1","id":80},{"width":3840,"height":2160,"codecs":"hev1","id":120},{"width":7680,"height":4320,"codecs":"unsupported"}]"""))!!
        assertEquals(3840,stream.getInt("width"))
    }
    private fun box(type: String, body: ByteArray) = ByteBuffer.allocate(body.size+8).putInt(body.size+8).put(type.toByteArray()).put(body).array()
    @Test fun readsTrackDimensionsAndRotationFromBoundedPrefix() {
        val header = ByteBuffer.allocate(84)
        header.putInt(40,65536); header.putInt(76,1920 shl 16);header.putInt(80,1080 shl 16)
        val video = box("moov",box("trak",box("tkhd",header.array())))
        assertEquals("1920 × 1080",mp4Resolution(video))
        header.putInt(40,0);header.putInt(44,65536)
        assertEquals("1080 × 1920",mp4Resolution(box("moov",box("trak",box("tkhd",header.array())))))
        assertEquals("",mp4Resolution(video.copyOf(40)))
        assertEquals("",mp4Resolution(byteArrayOf(0,0,0,1,109,111,111,118)))
    }
}
