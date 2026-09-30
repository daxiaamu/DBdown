package com.daxiaamu.dbdown

import org.junit.Assert.*
import org.junit.Test

class YoutubeStreamsTest {
    private fun video(id: String, height: Int, hdr: Boolean = false) = YoutubeStream("https://r.googlevideo.com/$id",true,false,"video/mp4; av01",height*16/9,height,60f,hdr,formatId=id)
    private fun audio(id: String, bitrate: Long, original: Boolean = true) = YoutubeStream("https://r.googlevideo.com/$id",false,true,"audio/webm; opus",bitrate=bitrate,original=original,formatId=id)
    @Test fun combinesLoggedInAndOtherClientsWithoutResolutionCap() {
        val selected=youtubeSelectStreams(listOf(video("web",1080),video("other",6480),audio("sound",160000))) { it }!!
        assertEquals(6480,selected.first.height)
        assertEquals("sound",selected.second!!.formatId)
    }
    @Test fun unavailableHighestFallsBackAndKeepsHdrAtSameResolution() {
        val candidates=listOf(video("12k",6480),video("sdr",4320).copy(bitrate=1000),video("hdr",4320,true),audio("a",160000))
        val selected=youtubeSelectStreams(candidates) { if(it.formatId=="12k") null else it }!!
        assertEquals("hdr",selected.first.formatId)
    }
    @Test fun failedAudioFallsBackBeforeGivingUpVideoOnly() {
        val selected=youtubeSelectStreams(listOf(video("4k",2160),audio("bad",192000),audio("good",128000),audio("dub",256000,false))) {
            if(it.formatId=="bad") null else it
        }!!
        assertEquals("good",selected.second!!.formatId)
    }
    @Test fun videoOnlyWithoutWorkingAudioIsNotSilentlySaved() {
        assertNull(youtubeSelectStreams(listOf(video("v",2160),audio("a",160000))) { if(it.audio) null else it })
        assertNotNull(youtubeSelectStreams(listOf(video("v",1080).copy(audio=true))) { it })
    }
    @Test fun duplicatesAreNotProbedTwice() {
        val v=video("v",2160); var calls=0
        youtubeSelectStreams(listOf(v,v,audio("a",160000))) { calls++; if(it.video) null else it }
        assertEquals(2,calls)
    }
    @Test fun manifestPathNIsTransformedWithoutChangingOtherPathParts() {
        assertEquals("https://r.googlevideo.com/api/n/decoded/manifest/hls?x=1",decodeYoutubeManifestUrl("id","https://r.googlevideo.com/api/n/encoded/manifest/hls?x=1") { _,url -> url.replace("n=encoded","n=decoded") })
    }
    @Test fun singleLanguageAudioDoesNotLoseToLowerBitrateDefaultLabel() {
        val selected=youtubeSelectStreams(listOf(video("v",2160),audio("opus",160000,false),
            audio("aac",128000,false).copy(defaultAudio=true,language="en"))) { it }!!
        assertEquals("opus",selected.second!!.formatId)
    }
    @Test fun mediaHostsAreRestricted() {
        assertTrue(youtubeMediaUrl("https://r1.googlevideo.com/videoplayback"))
        listOf("http://r1.googlevideo.com/x","https://googlevideo.com.evil.test/x","https://googlevideo.com:8443/x","https://u:p@googlevideo.com/x").forEach { assertFalse(it,youtubeMediaUrl(it)) }
    }
}
