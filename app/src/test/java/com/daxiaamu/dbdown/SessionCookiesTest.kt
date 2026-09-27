package com.daxiaamu.dbdown
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
class SessionCookiesTest {
    @Test fun sendsBootstrapCookieOnSecondRequestButNeverToAnotherDomain() {
        val jar = SessionCookies()
        val origin = "https://www.douyin.com/share/video/7641264887980657961/".toHttpUrl()
        jar.saveFromResponse(origin, listOf(Cookie.parse(origin, "ttwid=test-session; Path=/; Secure; HttpOnly")!!))
        assertEquals("test-session", jar.loadForRequest(origin).single().value)
        assertTrue(jar.loadForRequest("https://api.bilibili.com/".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("http://www.douyin.com/".toHttpUrl()).isEmpty())
    }
    @Test fun replacesAndDeletesCookiesUsingNormalServerSemantics() {
        val jar = SessionCookies()
        val origin = "https://www.douyin.com/".toHttpUrl()
        jar.saveFromResponse(origin, listOf(Cookie.parse(origin, "ttwid=one; Path=/")!!))
        jar.saveFromResponse(origin, listOf(Cookie.parse(origin, "ttwid=two; Path=/")!!))
        assertEquals(1, jar.loadForRequest(origin).size)
        assertEquals("two", jar.loadForRequest(origin).single().value)
        jar.saveFromResponse(origin, listOf(Cookie.parse(origin, "ttwid=gone; Max-Age=0; Path=/")!!))
        assertTrue(jar.loadForRequest(origin).isEmpty())
    }
}
