package com.daxiaamu.dbdown

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class YoutubeRawFormatsTest {
    private val link = Links.detect("t2dhvFTktk4")!!
    private fun video(id: Int, width: Int, hdr: Boolean, bitrate: Long = 1000000) = JSONObject()
        .put("itag", id).put("width", width).put("height", width * 9 / 16).put("fps", 60)
        .put("mimeType", "video/webm; codecs=\"vp9.2\"").put("bitrate", bitrate)
        .put("url", "https://r1.googlevideo.com/v$id")
        .put("colorInfo", JSONObject().put("transferCharacteristics", if(hdr) "COLOR_TRANSFER_CHARACTERISTICS_SMPTEST2084" else "COLOR_TRANSFER_CHARACTERISTICS_BT709"))
    private fun audio(codec: String, rate: Int) = JSONObject().put("itag", 9999)
        .put("mimeType", "audio/mp4; codecs=\"$codec\"").put("bitrate", rate)
        .put("url", "https://r1.googlevideo.com/$codec").put("audioChannels", if(codec == "ec-3") 6 else 2)
    private fun player(vararg formats: JSONObject, id: String = "t2dhvFTktk4") = JSONObject()
        .put("videoDetails", JSONObject().put("videoId", id).put("title", "Test"))
        .put("playabilityStatus", JSONObject().put("status", "OK"))
        .put("streamingData", JSONObject().put("adaptiveFormats", JSONArray(formats.toList())))
    private fun resolve(player: JSONObject) = youtubePlayerVideo(player, link) { _, url -> url }!!

    @Test fun hdrWinsAtSameResolutionAndFpsDespiteLowerBitrate() {
        val info = resolve(player(video(315,3840,false,90000000),video(337,3840,true,30000000),audio("mp4a.40.2",128000)))
        assertEquals("https://r1.googlevideo.com/v337",info.video)
    }
    @Test fun twelveKAndUnknownItagAreNotCappedAtEightK() {
        val info = resolve(player(video(9998,11520,true),video(402,7680,true),audio("mp4a.40.2",128000)))
        assertEquals("https://r1.googlevideo.com/v9998",info.video)
    }
    @Test fun eac3CanBeSelectedWithoutPretendingItIsAac() {
        val info = resolve(player(video(337,3840,true),audio("mp4a.40.2",128000),audio("ec-3",384000)))
        assertEquals("eac3",info.audioCodec)
        assertEquals("https://r1.googlevideo.com/ec-3",info.audio)
    }
    @Test fun ignoresMismatchedVideosAndDrmWhenCombiningResponses() {
        val protected = video(9998,11520,true).put("drmFamilies",JSONArray().put("WIDEVINE"))
        val merged = mergeYoutubePlayers(listOf(player(video(337,3840,true),audio("mp4a.40.2",128000),protected),
            player(video(9999,15360,true),id="b-Ag7meqZoU")),link)!!
        assertEquals("https://r1.googlevideo.com/v337",resolve(merged).video)
    }
}
