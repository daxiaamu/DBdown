package com.daxiaamu.dbdown
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object LoginPolicy {
    /** Web login redirects are not confined to a platform's domain list. */
    fun allowedNavigation(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.isHttps && parsed.username.isEmpty() && parsed.password.isEmpty()
    }
    /** Login never needs the recommendation feed's video streams. Keep scripts, images and CAPTCHA intact. */
    fun isFeedMedia(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return listOf("douyinvod.com", "bilivideo.com", "bilivideo.cn").any {
            parsed.host == it || parsed.host.endsWith(".$it")
        }
    }
}
