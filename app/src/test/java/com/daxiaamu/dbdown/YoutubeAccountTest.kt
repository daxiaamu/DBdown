package com.daxiaamu.dbdown

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray
import okhttp3.HttpUrl.Companion.toHttpUrl

class YoutubeAccountTest {
    @Test fun onlyExplicitConfigDeterminesLoginState() {
        assertEquals(AccountStatus.VALID, youtubeAccountVerdict("<script>ytcfg.set({\"LOGGED_IN\":true});</script>"))
        assertEquals(AccountStatus.EXPIRED, youtubeAccountVerdict("ytcfg.set({\"LOGGED_IN\":false});"))
        listOf("sign in", "<html>captcha</html>", "{\"LOGGED_IN\":false}", "ytcfg.set({});",
            "ytcfg.set({\"LOGGED_IN\":true});ytcfg.set({\"LOGGED_IN\":false});").forEach {
            assertEquals(AccountStatus.UNKNOWN,youtubeAccountVerdict(it))
        }
    }
    @Test fun googleNavigationAndCookieScopesStaySeparate() {
        assertTrue(LoginPolicy.allowedNavigation(Platform.YOUTUBE,"https://accounts.google.com/ServiceLogin"))
        assertTrue(LoginPolicy.allowedNavigation(Platform.YOUTUBE,"https://m.youtube.com/"))
        listOf("https://accounts.google.com.attacker.com/", "http://youtube.com/", "https://evil@youtube.com/",
            "https://www.youtube.com:8443/").forEach { assertFalse(LoginPolicy.allowedNavigation(Platform.YOUTUBE,it)) }
        assertTrue(usesWebCookies("https://www.youtube.com/watch".toHttpUrl()))
        listOf("https://accounts.google.com/", "https://youtube.googleapis.com/", "https://r1.googlevideo.com/",
            "https://youtube.com.evil.com/").forEach { assertFalse(usesWebCookies(it.toHttpUrl())) }
    }
    @Test fun authenticatedPlayerUsesMatchingVideoAndCompatibleAudio() {
        val video=JSONObject().put("itag",137).put("mimeType","video/mp4; codecs=\"avc1.640028\"")
            .put("width",1920).put("height",1080).put("url","https://r1.googlevideo.com/video").put("qualityLabel","1080p")
        val audio=JSONObject().put("mimeType","audio/mp4; codecs=\"mp4a.40.2\"")
            .put("url","https://r1.googlevideo.com/audio").put("bitrate",128000)
        val player=JSONObject().put("playabilityStatus",JSONObject().put("status","OK"))
            .put("videoDetails",JSONObject().put("videoId","BLKegH19KGI").put("title","Test"))
            .put("streamingData",JSONObject().put("adaptiveFormats",JSONArray().put(video).put(audio)))
        val link=Links.detect("BLKegH19KGI")!!
        val page="var ytInitialPlayerResponse = $player; next();"
        val info=youtubeWebVideo(page,link) { _, url -> url }!!
        assertEquals("1920 × 1080",info.resolution)
        assertEquals("https://r1.googlevideo.com/audio",info.audio)
        assertEquals(link.key,info.id)
        assertTrue(runCatching { youtubeWebVideo(page,Links.detect("qIzGvexMjpA")!!) { _,url -> url } }.isFailure)
        assertNull(youtubeWebVideo("<html>verification required</html>",link))
    }
}
