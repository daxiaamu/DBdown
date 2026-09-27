package com.daxiaamu.dbdown
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object LoginPolicy {
    fun allowedNavigation(platform: Platform, url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
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
