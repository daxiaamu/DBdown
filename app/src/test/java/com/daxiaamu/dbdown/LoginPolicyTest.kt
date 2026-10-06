package com.daxiaamu.dbdown
import org.junit.Assert.*
import org.junit.Test

class LoginPolicyTest {
    @Test fun httpsNavigationHasNoPlatformDomainAllowlist() {
        listOf("https://passport.bilibili.com/", "https://gds.google.com/web/landing",
            "https://passport.krcom.cn/sso/crossdomain?action=logout", "https://new-login.example.org/",
            "https://new-login.example.org:8443/").forEach { assertTrue(it, LoginPolicy.allowedNavigation(it)) }
    }
    @Test fun nonWebSchemesAndCredentialBearingUrlsRemainSeparate() {
        listOf("http://example.org/", "intent://login", "bilibili://home", "javascript:alert(1)",
            "file:///sdcard/test", "https://user:password@example.org/", "not a url")
            .forEach { assertFalse(it, LoginPolicy.allowedNavigation(it)) }
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
