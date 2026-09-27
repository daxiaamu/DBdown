package com.daxiaamu.dbdown
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class WebCookieScopeTest {
    @Test fun webCookiesOnlyApplyToHttpsPlatformDomains() {
        listOf("https://api.bilibili.com/x/player/playurl", "https://www.douyin.com/share/video/1",
            "https://www.iesdouyin.com/").forEach { assertTrue(usesWebCookies(it.toHttpUrl())) }
        listOf("https://bilibili.com.evil.example/", "https://evildouyin.com/", "http://www.douyin.com/",
            "https://cdn.example/video.mp4", "https://bilivideo.com/").forEach { assertFalse(usesWebCookies(it.toHttpUrl())) }
    }
}
