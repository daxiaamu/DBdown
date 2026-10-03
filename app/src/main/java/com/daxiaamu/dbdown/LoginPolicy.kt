package com.daxiaamu.dbdown
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object LoginPolicy {
    fun allowedNavigation(platform: Platform, url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        if(!parsed.isHttps || parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || parsed.port != 443) return false
        if(platform == Platform.YOUTUBE) return parsed.host == "youtube.com" || parsed.host.endsWith(".youtube.com") ||
            parsed.host in setOf("accounts.google.com", "myaccount.google.com", "www.google.com")
        if(platform == Platform.WEIBO) return listOf("weibo.com","weibo.cn").any {
            parsed.host == it || parsed.host.endsWith(".$it")
        } || parsed.host in setOf("login.sina.com.cn", "passport.sina.cn")
        val root = if(platform == Platform.BILI) "bilibili.com" else "douyin.com"
        return parsed.isHttps && (parsed.host == root || parsed.host.endsWith(".$root"))
    }
    /** Login never needs the recommendation feed's video streams. Keep scripts, images and CAPTCHA intact. */
    fun isFeedMedia(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return listOf("douyinvod.com", "bilivideo.com", "bilivideo.cn").any {
            parsed.host == it || parsed.host.endsWith(".$it")
        }
    }
}
