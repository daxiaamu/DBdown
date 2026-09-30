package com.daxiaamu.dbdown

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MediaSpecificationsTest {
    private val link=Links.detect("BV1xx411c7mD")!!
    private fun dash() = JSONObject("""{"video":[
      {"id":80,"width":1920,"height":1080,"frame_rate":"60000/1001","codecs":"avc1","baseUrl":"https://media.example/high"},
      {"id":64,"width":1280,"height":720,"frame_rate":"30","codecs":"avc1","baseUrl":"https://media.example/low"}],
      "audio":[{"id":30280,"codecs":"mp4a.40.2","bandwidth":192000,"baseUrl":"https://media.example/aac"}],
      "flac":{"audio":{"id":30251,"codecs":"flac","bandwidth":800000,"baseUrl":"https://media.example/flac"}}}""")
    @Test fun selectedBiliVideoAndAudioAreCombinedExactly() {
        val initial=biliVideoInfo(link,"title",dash(),null)
        assertEquals(59.94f,initial.fps,0.01f)
        val catalog=initial.specifications!!
        val selection=TrackSelection(catalog.videos.last().id,catalog.audios.last().id)
        val chosen=biliVideoInfo(link,"title",dash(),selection)
        assertEquals("https://media.example/low",chosen.video)
        assertEquals("https://media.example/aac",chosen.audio)
        assertEquals("1280 × 720",chosen.resolution)
        assertEquals(30f,chosen.fps,0f)
        assertEquals(selection,chosen.specifications!!.selected)
        assertTrue(runCatching { biliVideoInfo(link,"title",dash(),selection.copy(video="gone")) }.isFailure)
    }
    @Test fun frameRatesAreNotGuessedAndRationalsKeepPrecision() {
        assertEquals("29.97 fps",fpsLabel(frameRate("30000/1001")))
        assertEquals("60 fps",fpsLabel(60f))
        assertEquals("",fpsLabel(frameRate("0/0")))
        assertEquals("1920 × 1080",videoSpecification("1920 × 1080",0f))
    }
    @Test fun youtubeSpecIdsIgnoreExpiringUrlButDistinguishFrameRateAndAudioLanguage() {
        val video=YoutubeStream("https://v.googlevideo.com/one",true,false,"video/mp4; codecs=av01",3840,2160,60f,formatId="401")
        assertEquals(youtubeTrackId(video),youtubeTrackId(video.copy(url="https://v.googlevideo.com/two",source="other")))
        assertNotEquals(youtubeTrackId(video),youtubeTrackId(video.copy(fps=30f)))
        val audio=YoutubeStream("https://v.googlevideo.com/a",false,true,"audio/webm; codecs=opus",language="en",formatId="251")
        assertNotEquals(youtubeTrackId(audio),youtubeTrackId(audio.copy(language="zh")))
        val specs=youtubeSpecifications(listOf(video,video.copy(url="https://v.googlevideo.com/two"),audio),video to audio)
        assertEquals(1,specs.videos.size)
        assertFalse(specs.videos.first().hasAudio)
        assertEquals(youtubeTrackId(audio),specs.selected.audio)
    }
    @Test fun embeddedAudioClearsExternalSelectionAndInitialChoiceHasNoExtraAudio() {
        val catalog=MediaSpecifications(listOf(TrackOption("muxed","720p",hasAudio=true),TrackOption("silent","4K")),
            listOf(TrackOption("aac","AAC")),TrackSelection("silent","aac"))
        assertEquals(TrackSelection("silent"),catalog.selectVideo("silent"))
        assertEquals(TrackSelection("muxed"),catalog.selectVideo("muxed",TrackSelection("silent","aac")))
        assertEquals(TrackSelection("silent","aac"),catalog.selectVideo("silent",TrackSelection("silent","aac")))
        assertEquals(TrackSelection("silent"),catalog.selectVideo("silent",TrackSelection("muxed")))
    }
    @Test fun explicitBiliVideoOnlyDoesNotAddAudioButAutomaticDownloadStillDoes() {
        val initial=biliVideoInfo(link,"title",dash(),null)
        assertNotNull(initial.audio)
        val choice=TrackSelection(initial.specifications!!.selected.video)
        val silent=biliVideoInfo(link,"title",dash(),choice)
        assertNull(silent.audio)
        assertEquals(choice,silent.specifications!!.selected)
        assertTrue(silent.audioFallbacks.isEmpty())
    }
    @Test fun youtubeSilentDownloadIsOptIn() {
        val video=YoutubeStream("https://v.googlevideo.com/video",true,false,"video/mp4; codecs=avc1",1920,1080)
        assertNull(youtubeSelectStreams(listOf(video)) { it })
        assertEquals(video,youtubeSelectStreams(listOf(video),allowSilent=true) { it }!!.first)
        assertNull(youtubeSelectStreams(listOf(video),allowSilent=true) { it }!!.second)
    }
}
