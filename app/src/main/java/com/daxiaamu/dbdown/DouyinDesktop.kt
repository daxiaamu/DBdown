package com.daxiaamu.dbdown

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.RenderProcessGoneDetail
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener
import kotlin.coroutines.resume

/** Loads official desktop variants and Live Photo clips omitted by mobile sharing. */
internal object DouyinDesktop {
    private lateinit var context: Context
    private val mutex = Mutex()
    fun initialize(context: Context) { this.context = context.applicationContext }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun detail(id: String): String = mutex.withLock {
        require(id.matches(Regex("[0-9]+")))
        withContext(Dispatchers.Main) {
            val browser = WebView(context)
            var rendererGone = false
            try {
                browser.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = VideoResolver.DESKTOP
                    allowFileAccess = false
                    allowContentAccess = false
                    blockNetworkImage = true
                    mediaPlaybackRequiresUserGesture = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(browser, false)
                browser.webViewClient = object : WebViewClient() {
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        rendererGone = true
                        return true
                    }
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        request.url.scheme != "https" || request.url.host != "www.douyin.com"
                }
                browser.loadUrl("https://www.douyin.com/jingxuan?modal_id=$id")
                withTimeoutOrNull(25_000) {
                    var detail: String? = null
                    while(detail == null) {
                        delay(300)
                        check(!rendererGone) { "作品资源网页加载失败，请重试" }
                        val result = suspendCancellableCoroutine<String> { continuation ->
                            browser.evaluateJavascript("""(function(){var e=document.getElementById('RENDER_DATA');if(!e)return null;try{var d=JSON.parse(decodeURIComponent(e.textContent));var v=d.app&&d.app.videoDetail;return v&&v.awemeId==='$id'?JSON.stringify(v):null;}catch(e){return null;}})()""") {
                                if(continuation.isActive) continuation.resume(it)
                            }
                        }
                        detail = JSONTokener(result).nextValue() as? String
                    }
                    detail
                } ?: error("未能获取完整作品资源，请在设置中打开抖音登录页完成验证后重试")
            } finally {
                browser.stopLoading()
                browser.destroy()
            }
        }
    }
}
