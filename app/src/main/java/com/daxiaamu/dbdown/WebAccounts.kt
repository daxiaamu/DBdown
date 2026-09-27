package com.daxiaamu.dbdown

import android.webkit.CookieManager
import android.webkit.WebStorage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.atomic.AtomicBoolean

object WebAccounts {
    private lateinit var manager: CookieManager
    private val state = MutableStateFlow<Map<Platform, Boolean>>(emptyMap())
    val accounts = state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val refreshing = AtomicBoolean(false)
    fun initialize() {
        manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        refresh()
    }
    /** CookieManager can wait for Chromium's cookie thread. Never do that on the UI thread. */
    fun refresh() {
        if(!refreshing.compareAndSet(false, true)) return
        scope.launch {
            try { mutex.withLock { readState() } }
            finally { refreshing.set(false) }
        }
    }
    private fun readState() {
        state.value = mapOf(
            Platform.BILI to hasCookie("https://www.bilibili.com/", setOf("SESSDATA")),
            Platform.DOUYIN to hasCookie("https://www.douyin.com/", setOf("sessionid", "sessionid_ss", "sid_guard"))
        )
    }
    private fun hasCookie(url: String, names: Set<String>) =
        manager.getCookie(url).orEmpty().split(';').any {
            it.trim().substringBefore('=') in names && it.substringAfter('=', "").isNotBlank()
        }
    /** Flush does disk I/O; completion and lifecycle callbacks must return immediately. */
    fun flush() {
        scope.launch { mutex.withLock { manager.flush(); readState() } }
    }
    fun clearAll(done: () -> Unit) {
        manager.removeAllCookies {
            WebStorage.getInstance().deleteAllData()
            scope.launch {
                mutex.withLock { manager.flush(); readState() }
                withContext(Dispatchers.Main) { done() }
            }
        }
    }
    fun cookies(url: HttpUrl): List<Cookie> {
        if(!usesWebCookies(url)) return emptyList()
        return manager.getCookie(url.toString()).orEmpty().split(';').mapNotNull {
            Cookie.parse(url, it.trim())
        }
    }
    fun save(url: HttpUrl, cookies: List<Cookie>) {
        if(usesWebCookies(url)) cookies.forEach { manager.setCookie(url.toString(), it.toString()) }
    }
}

fun usesWebCookies(url: HttpUrl): Boolean = url.isHttps &&
    listOf("bilibili.com", "douyin.com", "iesdouyin.com").any {
        url.host == it || url.host.endsWith(".$it")
    }

class PlatformCookieJar : CookieJar {
    private val anonymous = SessionCookies()
    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        if(usesWebCookies(url)) WebAccounts.cookies(url) else anonymous.loadForRequest(url)
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if(usesWebCookies(url)) WebAccounts.save(url, cookies) else anonymous.saveFromResponse(url, cookies)
    }
}
