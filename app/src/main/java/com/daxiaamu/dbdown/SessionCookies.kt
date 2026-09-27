package com.daxiaamu.dbdown

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** Anonymous platform bootstrap cookies stay in memory and follow normal domain/path rules. */
class SessionCookies : CookieJar {
    private val cookies = mutableListOf<Cookie>()
    @Synchronized override fun saveFromResponse(url: HttpUrl, incoming: List<Cookie>) {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt <= now }
        incoming.forEach { value ->
            cookies.removeAll { it.name == value.name && it.domain == value.domain && it.path == value.path }
            if(value.expiresAt > now) cookies.add(value)
        }
        while(cookies.size > 100) cookies.removeAt(0)
    }
    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
        return cookies.filter { it.matches(url) }
    }
}
