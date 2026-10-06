package com.daxiaamu.dbdown

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WeiboTest {
    @Test fun postLinksAndShareTextNormalizeWithoutTracking() {
        for(url in listOf("https://weibo.com/5926682210/FBqgOmDxO?from=app","https://m.weibo.cn/status/FBqgOmDxO","https://weibo.cn/comment/FBqgOmDxO")) {
            val link=Links.detect("分享视频 [$url] 快来看")!!
            assertEquals(Platform.WEIBO,link.platform); assertEquals("wb:FBqgOmDxO",link.key)
        }
        assertEquals("wb:4189191225395228",Links.detect("https://m.weibo.cn/detail/4189191225395228")!!.key)
        assertEquals("weibo-short:/RHbmjzW",Links.detect("分享 http://t.cn/RHbmjzW")!!.key)
        assertEquals("wb-video:1034:4797699866951785",Links.detect("https://video.weibo.com/show?fid=1034%3A4797699866951785")!!.key)
    }
    @Test fun rejectsProfilesAndForgedDomains() {
        for(url in listOf("https://weibo.com/u/12345678","https://weibo.com","https://weibo.com.evil.com/123/FBqgOmDxO","https://weibo.com@evil.com/123/FBqgOmDxO","https://m.weibo.cn/detail/no","https://video.weibo.com/show?fid=x")) assertNull(url,Links.detect(url))
        assertNull(WeiboMedia.mediaUrl("https://sinaimg.cn.evil.com/video.mp4"))
        assertNull(WeiboMedia.mediaUrl("https://user:pass@sinaimg.cn/video.mp4"))
        assertNull(WeiboMedia.mediaUrl("http://127.0.0.1/video.mp4"))
    }
    private val link get()=Links.detect("https://m.weibo.cn/detail/4189191225395228")!!
    private fun post()=JSONObject("""{"idstr":"4189191225395228","text_raw":"示例标题","page_info":{"object_id":"1034:4797699866951785","media_info":{"playback_list":[
        {"play_info":{"url":"https://f.video.weibocdn.com/low.mp4","label":"low","width":1280,"height":720,"fps":30,"video_codecs":"avc1","audio_codecs":"mp4a","bitrate":1000000}},
        {"play_info":{"url":"https://f.video.weibocdn.com/high.mp4","label":"high","width":1920,"height":1080,"fps":60,"video_codecs":"avc1","audio_codecs":"mp4a","bitrate":3000000}}
        ]}}}""")
    @Test fun highestVariantAndExplicitSelectionUseEmbeddedAudio() {
        val info=WeiboMedia.parse(post(),link)
        assertTrue(info.video.endsWith("high.mp4")); assertEquals("1920 × 1080",info.resolution); assertEquals(60f,info.fps)
        val specs=info.specifications!!; assertEquals(2,specs.videos.size); assertTrue(specs.videos.all { it.hasAudio }); assertTrue(specs.audios.isEmpty())
        val low=WeiboMedia.parse(post(),link,TrackSelection(specs.videos.last().id))
        assertTrue(low.video.endsWith("low.mp4"))
        assertThrows(IllegalStateException::class.java) { WeiboMedia.parse(post(),link,TrackSelection("removed")) }
        assertThrows(IllegalStateException::class.java) { WeiboMedia.parse(post(),link,TrackSelection(specs.selected.video,"extra")) }
    }
    @Test fun originalPhotosPreserveOrderAndTextOnlyIsRejected() {
        val post=JSONObject("""{"idstr":"4189191225395228","text":"<b>图片</b>&amp;更多","pic_ids":["b","a"],"pic_infos":{"a":{"largest":{"url":"https://wx1.sinaimg.cn/large/a.jpg"}},"b":{"largest":{"url":"https://wx1.sinaimg.cn/large/b.jpg"}}}}""")
        val info=WeiboMedia.parse(post,link)
        assertTrue(info.images.first().endsWith("b.jpg")); assertEquals(2,info.images.size)
        assertEquals("图片 &更多",info.title)
        assertThrows(IllegalStateException::class.java) { WeiboMedia.parse(JSONObject("""{"idstr":"4189191225395228"}"""),link) }
    }
    @Test fun webLoginNavigationAndDownloadCookiesHaveSeparateScopes() {
        for(url in listOf("https://passport.weibo.cn/signin/login","https://passport.weibo.com/sso/signin","https://login.sina.com.cn/sso/login.php")) assertTrue(LoginPolicy.allowedNavigation(url))
        for(url in listOf("http://weibo.com/","https://weibo.com@evil.com/")) assertFalse(LoginPolicy.allowedNavigation(url))
        assertTrue(usesWebCookies("https://weibo.com/ajax/statuses/show".toHttpUrl()))
        assertFalse(usesWebCookies("https://f.video.weibocdn.com/a.mp4".toHttpUrl()))
        assertEquals(AccountStatus.VALID,accountVerdict(Platform.WEIBO,"""{"ok":1,"data":{"login":true}}"""))
        assertEquals(AccountStatus.EXPIRED,accountVerdict(Platform.WEIBO,"""{"ok":1,"data":{"login":false}}"""))
        assertEquals(AccountStatus.UNKNOWN,accountVerdict(Platform.WEIBO,"""{"ok":0,"msg":"验证"}"""))
        assertEquals(AccountStatus.UNKNOWN,accountVerdict(Platform.WEIBO,"<html>访问验证</html>"))
    }
}
