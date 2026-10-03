package com.daxiaamu.dbdown
import org.junit.Assert.*
import org.junit.Test

class LoginPolicyTest {
    @Test fun loginNavigationDoesNotEscapeTheChosenOfficialPlatform() {
        assertTrue(LoginPolicy.allowedNavigation(Platform.BILI, "https://passport.bilibili.com/h5-app/passport/login"))
        assertFalse(LoginPolicy.allowedNavigation(Platform.BILI, "https://passport.bilibili.com.attacker.example/login"))
        assertFalse(LoginPolicy.allowedNavigation(Platform.BILI, "https://www.douyin.com/"))
        assertFalse(LoginPolicy.allowedNavigation(Platform.DOUYIN, "intent://login"))
        assertFalse(LoginPolicy.allowedNavigation(Platform.DOUYIN, "http://www.douyin.com/"))
    }
    @Test fun weiboQrLoginAllowsSinaCallbackWithoutOpeningOtherOrigins() {
        assertTrue(LoginPolicy.allowedNavigation(Platform.WEIBO, "https://passport.sina.cn/sso/crossdomain?ticket=fixture"))
        for(url in listOf("https://passport.sina.cn.evil.com/", "https://passport.sina.cn@evil.com/",
            "https://evil.sina.cn/", "http://passport.sina.cn/", "https://passport.sina.cn:8080/"))
            assertFalse(LoginPolicy.allowedNavigation(Platform.WEIBO, url))
        assertFalse(LoginPolicy.allowedNavigation(Platform.BILI, "https://passport.sina.cn/"))
    }
    @Test fun loginMediaFilterLeavesCaptchaAndLoginResourcesAlone() {
        assertTrue(LoginPolicy.isFeedMedia("https://v3.douyinvod.com/video.mp4"))
        assertTrue(LoginPolicy.isFeedMedia("https://cn.example.bilivideo.com/video.m4s"))
        listOf("https://static.geetest.com/captcha.js", "https://lf-rc1.yhgfb-cn-static.com/captcha.js",
            "https://p-pc-weboff.byteimg.com/login.png", "https://passport.bilibili.com/sms",
            "https://www.douyin.com/passport/web/send_code/", "https://douyinvod.com.attacker.example/a")
            .forEach { assertFalse(LoginPolicy.isFeedMedia(it)) }
    }
}
