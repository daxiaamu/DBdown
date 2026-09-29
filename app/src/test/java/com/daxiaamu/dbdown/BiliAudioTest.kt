package com.daxiaamu.dbdown

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BiliAudioTest {
    @Test fun selectsHighestActualBitrateRegardlessOfOrder() {
        val dash = JSONObject("""{"audio":[
            {"id":30232,"baseUrl":"https://example.com/audio","codecs":"mp4a.40.2","bandwidth":98331},
            {"id":30280,"baseUrl":"https://example.com/audio","codecs":"mp4a.40.2","bandwidth":202024},
            {"id":30216,"baseUrl":"https://example.com/audio","codecs":"mp4a.40.2","bandwidth":65724}],
            "flac":{"display":true,"audio":null},"dolby":{"type":0,"audio":null}}""")
        assertEquals(30280, bestBiliAudio(dash)!!.getInt("id"))
    }
    @Test fun prefersReturnedFlacOverAac() {
        val dash = JSONObject("""{"audio":[{"id":30280,"baseUrl":"https://example.com/audio","codecs":"mp4a.40.2","bandwidth":202024}],
            "flac":{"audio":{"id":30251,"baseUrl":"https://example.com/audio","codecs":"fLaC","bandwidth":1000000}}}""")
        assertEquals(30251, bestBiliAudio(dash)!!.getInt("id"))
    }
    @Test fun comparesAllReturnedCompatibleCandidates() {
        val dash = JSONObject("""{"audio":[{"id":1,"baseUrl":"https://example.com/audio","codecs":"mp4a.40.2","bandwidth":100}],
            "dolby":{"audio":[{"id":2,"baseUrl":"https://example.com/audio","codecs":"mp4a.40.2","bandwidth":200}]}}""")
        assertEquals(2, bestBiliAudio(dash)!!.getInt("id"))
        assertNull(bestBiliAudio(JSONObject("""{"audio":null}""")))
    }
}
