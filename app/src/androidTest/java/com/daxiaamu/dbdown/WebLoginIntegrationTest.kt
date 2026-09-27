package com.daxiaamu.dbdown

import android.content.Intent
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebLoginIntegrationTest {
    @Test fun webCookiesReachDownloaderWithDomainAndPathIsolationAndServerDeletion() {
        val manager = CookieManager.getInstance()
        val jar = PlatformCookieJar()
        val url = "https://www.douyin.com/dbdownloader-test/probe".toHttpUrl()
        try {
            manager.setCookie(url.toString(), "db_test=fixture; Path=/dbdownloader-test/; Secure; HttpOnly")
            manager.flush()
            assertEquals("fixture", jar.loadForRequest(url).first { it.name == "db_test" }.value)
            assertFalse(jar.loadForRequest("https://www.douyin.com/".toHttpUrl()).any { it.name == "db_test" })
            assertFalse(jar.loadForRequest("https://api.bilibili.com/".toHttpUrl()).any { it.name == "db_test" })
            assertFalse(jar.loadForRequest("https://www.douyin.com.evil.example/".toHttpUrl()).any { it.name == "db_test" })
            jar.saveFromResponse(url, listOf(Cookie.parse(url, "db_test=updated; Path=/dbdownloader-test/; Secure")!!))
            assertEquals("updated", jar.loadForRequest(url).first { it.name == "db_test" }.value)
            jar.saveFromResponse(url, listOf(Cookie.parse(url, "db_test=; Path=/dbdownloader-test/; Max-Age=0; Secure")!!))
            assertFalse(jar.loadForRequest(url).any { it.name == "db_test" })
        } finally {
            manager.setCookie(url.toString(), "db_test=; Path=/dbdownloader-test/; Max-Age=0; Secure")
            manager.flush()
        }
    }

    @Test fun bothOfficialLoginPagesOpenInWebView() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for(platform in Platform.entries) {
            ActivityScenario.launch<LoginActivity>(
                Intent(context, LoginActivity::class.java).putExtra("platform", platform.name)
            ).use { scenario ->
                var ready = false
                var attempts = 0
                var url = ""
                val deadline = System.currentTimeMillis() + 120000
                while(System.currentTimeMillis() < deadline && !ready) {
                    Thread.sleep(500)
                    scenario.onActivity { activity ->
                        fun find(view: android.view.View): WebView? {
                            if(view is WebView) return view
                            if(view is android.view.ViewGroup) for(index in 0 until view.childCount) {
                                find(view.getChildAt(index))?.let { return it }
                            }
                            return null
                        }
                        val web = find(activity.window.decorView)
                        url = web?.url.orEmpty()
                        if(web != null && url.startsWith("https://")) {
                            if(platform == Platform.DOUYIN && attempts++ % 6 == 0) {
                                web.evaluateJavascript("(function(){var e=Array.from(document.querySelectorAll('button,[role=button],span,div')).reverse().find(e=>e.innerText.trim()==='登录'&&e.getBoundingClientRect().width>0&&e.getBoundingClientRect().height>0&&e.getBoundingClientRect().top>=0&&e.getBoundingClientRect().bottom<=innerHeight);if(e){e.click();return true;}return false;})()") { }
                            }
                            web.evaluateJavascript("/获取验证码|验证码登录|密码登录|扫码登录|短信登录/.test(document.body?.innerText || '')") { ready = it == "true" }
                        }
                    }
                }
                assertTrue("Official login form should be visible: " + platform, ready)
                assertTrue(url.contains(if(platform == Platform.BILI) "bilibili.com" else "douyin.com"))
                val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                java.io.File(context.getExternalFilesDir(null), "login-" + platform.name + ".png").outputStream().use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
            }
        }
    }
    @Test fun bothResolversStillWorkWithWebCookieJar() = kotlinx.coroutines.runBlocking {
        val bili = VideoResolver().resolve(Links.detect("BV1xx411c7mD")!!)
        assertTrue(bili.video.startsWith("https://"))
        assertNotNull(bili.audio)
        val douyin = VideoResolver().resolve(Links.detect("https://www.douyin.com/video/7679018882060990022")!!)
        assertTrue(douyin.video.startsWith("https://"))
        assertTrue(douyin.title.isNotBlank())
    }}
