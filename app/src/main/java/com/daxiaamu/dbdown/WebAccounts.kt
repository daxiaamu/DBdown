package com.daxiaamu.dbdown

import android.content.Context
import android.content.SharedPreferences
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
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
    private lateinit var prefs: SharedPreferences
    private val statusState = MutableStateFlow<Map<Platform, AccountStatus>>(emptyMap())
    val statuses = statusState.asStateFlow()
    private val promptState = MutableStateFlow<Set<Platform>>(emptySet())
    val expiredPrompt = promptState.asStateFlow()
    private val dismissed = java.util.concurrent.ConcurrentHashMap.newKeySet<Platform>()
    private val recheck = AtomicBoolean(false)
    private var lastCheck = 0L
    private val checker = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(false).build()
    fun initialize(context: Context) {
        manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        prefs = context.getSharedPreferences("account_validity", Context.MODE_PRIVATE)
        refresh()
    }
    private fun url(platform: Platform) = when(platform) {
        Platform.WEIBO -> "https://m.weibo.cn/"
        Platform.BILI -> "https://www.bilibili.com/"
        Platform.DOUYIN -> "https://www.douyin.com/"
        Platform.YOUTUBE -> "https://www.youtube.com/"
    }
    internal fun youtubeSessionPresent(): Boolean = ::manager.isInitialized &&
        hasSession(Platform.YOUTUBE, manager.getCookie(url(Platform.YOUTUBE)).orEmpty())
    internal fun youtubeCookieSnapshot(): String = if(::manager.isInitialized)
        manager.getCookie(url(Platform.YOUTUBE)).orEmpty() else ""
    internal fun observeYoutubeSession(page: String, snapshot: String) {
        if(snapshot.isBlank()) return
        val verdict = youtubeAccountVerdict(page)
        if(verdict == AccountStatus.UNKNOWN) return
        scope.launch { mutex.withLock {
            if(youtubeCookieSnapshot() == snapshot) publish(Platform.YOUTUBE, verdict)
        } }
    }
    private fun hasSession(platform: Platform, raw: String): Boolean {
        val names = when(platform) {
            Platform.WEIBO -> setOf("SUB")
            Platform.BILI -> setOf("SESSDATA")
            Platform.DOUYIN -> setOf("sessionid", "sessionid_ss", "sid_guard")
            Platform.YOUTUBE -> setOf("SAPISID", "__Secure-1PAPISID", "__Secure-3PAPISID", "SID")
        }
        return raw.split(';').any { it.trim().substringBefore('=') in names && it.substringAfter('=', "").isNotBlank() }
    }
    /** Cookie and network I/O never run on the UI thread. */
    fun refresh(force: Boolean = false) {
        if(!refreshing.compareAndSet(false, true)) { if(force) recheck.set(true); return }
        scope.launch {
            try { mutex.withLock {
                val now = android.os.SystemClock.elapsedRealtime()
                val check = force || lastCheck == 0L || now - lastCheck >= 300_000L
                for(platform in Platform.accountPlatforms) {
                    val raw = manager.getCookie(url(platform)).orEmpty()
                    val present = hasSession(platform, raw)
                    state.value = state.value + (platform to present)
                    val known = prefs.getBoolean(platform.name + "_seen", false)
                    if(!present) {
                        publish(platform, if(known) AccountStatus.EXPIRED else AccountStatus.SIGNED_OUT)
                        continue
                    }
                    if(platform != Platform.WEIBO) prefs.edit().putBoolean(platform.name + "_seen", true).apply()
                    if(!check && statusState.value[platform] != null) continue
                    val wasExpired = prefs.getBoolean(platform.name + "_expired", false)
                    publish(platform, if(wasExpired) AccountStatus.EXPIRED else AccountStatus.CHECKING)
                    val endpoint = when(platform) {
                        Platform.WEIBO -> "https://m.weibo.cn/api/config"
                        Platform.BILI -> "https://api.bilibili.com/x/web-interface/nav"
                        Platform.DOUYIN -> "https://www.douyin.com/aweme/v1/web/user/profile/self/?aid=6383&device_platform=webapp"
                        Platform.YOUTUBE -> "https://www.youtube.com/?hl=en"
                    }
                    val cookie = manager.getCookie(endpoint).orEmpty()
                    val verdict = runCatching {
                        checker.newCall(Request.Builder().url(endpoint).header("Cookie", cookie)
                            .header("Referer", url(platform)).header("User-Agent", VideoResolver.DESKTOP).build())
                            .execute().use { response ->
                                if(response.isSuccessful) accountVerdict(platform, response.body?.string().orEmpty())
                                else AccountStatus.UNKNOWN
                            }
                    }.getOrDefault(AccountStatus.UNKNOWN)
                    // A WebView login may change credentials while the request is in flight.
                    if(manager.getCookie(url(platform)).orEmpty() == raw) {
                        if(verdict == AccountStatus.VALID) prefs.edit().putBoolean(platform.name + "_seen", true).apply()
                        val effective=if(platform == Platform.WEIBO && verdict == AccountStatus.EXPIRED && !known) AccountStatus.SIGNED_OUT else verdict
                        publish(platform, if(effective == AccountStatus.UNKNOWN && wasExpired) AccountStatus.EXPIRED else effective)
                    } else { recheck.set(true) }
                }
                if(check) lastCheck = now
            } } finally { refreshing.set(false); if(recheck.getAndSet(false)) refresh(true) }
        }
    }
    private fun publish(platform: Platform, status: AccountStatus) {
        statusState.value = statusState.value + (platform to status)
        if(status == AccountStatus.EXPIRED) {
            prefs.edit().putBoolean(platform.name + "_expired", true).apply()
            if(platform !in dismissed) promptState.value = promptState.value + platform
        } else if(status == AccountStatus.VALID || status == AccountStatus.SIGNED_OUT) {
            prefs.edit().remove(platform.name + "_expired").apply()
            dismissed.remove(platform)
            promptState.value = promptState.value - platform
        }
    }
    fun dismissExpiry() {
        dismissed.addAll(promptState.value); promptState.value = emptySet()
    }
    fun flush() {
        scope.launch {
            mutex.withLock { manager.flush(); lastCheck = 0L }
            refresh(true)
        }
    }
    fun clearAll(done: () -> Unit) {
        scope.launch {
            mutex.withLock {
                withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        manager.removeAllCookies { continuation.resume(Unit) { _, _, _ -> } }
                        WebStorage.getInstance().deleteAllData()
                    }
                }
                manager.flush(); prefs.edit().clear().apply()
                dismissed.clear(); promptState.value = emptySet(); state.value = emptyMap()
                statusState.value = Platform.accountPlatforms.associateWith { AccountStatus.SIGNED_OUT }
                lastCheck = 0L
            }
            withContext(Dispatchers.Main) { done() }
        }
    }
    fun cookies(url: HttpUrl): List<Cookie> {
        if(!::manager.isInitialized || !usesWebCookies(url)) return emptyList()
        return manager.getCookie(url.toString()).orEmpty().split(';').mapNotNull {
            Cookie.parse(url, it.trim())
        }
    }
    fun save(url: HttpUrl, cookies: List<Cookie>) {
        if(::manager.isInitialized && usesWebCookies(url)) cookies.forEach { manager.setCookie(url.toString(), it.toString()) }
    }
}

fun usesWebCookies(url: HttpUrl): Boolean = url.isHttps &&
    listOf("bilibili.com", "douyin.com", "iesdouyin.com", "youtube.com", "weibo.com", "weibo.cn").any {
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
