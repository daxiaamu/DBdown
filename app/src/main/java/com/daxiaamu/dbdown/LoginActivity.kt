package com.daxiaamu.dbdown

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.*
import androidx.activity.viewModels
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

class LoginActivity : ComponentActivity() {
    private val clipboardModel by viewModels<MainViewModel>()
    internal var browser: WebView? = null
        private set
    private val platform by lazy { Platform.accountPlatforms.firstOrNull { it.name == intent.getStringExtra("platform") } ?: Platform.BILI }
    private val startUrl get() = when(platform) {
        Platform.WEIBO -> "https://passport.weibo.cn/signin/login?entry=mweibo&r=https%3A%2F%2Fm.weibo.cn%2F"
        Platform.BILI -> "https://passport.bilibili.com/h5-app/passport/login"
        Platform.DOUYIN -> "https://www.douyin.com/jingxuan"
        Platform.YOUTUBE -> "https://www.youtube.com/signin?next=%2F&hl=zh-CN"
    }
    private var desktopFallback = false
    private var loadingProgress by mutableIntStateOf(0)
    private var message by mutableStateOf("")
    private var host by mutableStateOf("")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycle.addObserver(ForegroundClipboardObserver(this, {
            getSharedPreferences("settings", 0).getBoolean("clipboard", true)
        }) { text -> clipboardModel.inspectClipboard(text) })
        desktopFallback = savedInstanceState?.getBoolean("desktop_fallback", false) ?: false
        // The WebView has one Activity owner, independent of Compose recomposition.
        val view = createBrowser(savedInstanceState)
        browser = view
        setContent {
            DownloaderTheme {
                val haze=remember { HazeState() }
                BackHandler { finish() }
                Box(Modifier.fillMaxSize()) {
                Surface(Modifier.fillMaxSize().hazeSource(haze)) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                        LoginToolbar()
                        // Keep the web viewport stable: changing progress must not resize the page.
                        Box(Modifier.fillMaxWidth().height(3.dp)) {
                            if(loadingProgress < 100) AppProgressBar(progress = { loadingProgress / 100f }, modifier = Modifier.fillMaxSize())
                        }
                        AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(),
                            factory = { view }, onRelease = { releaseBrowser(it) })
                    }
                }
                ClipboardSuggestionOverlay(clipboardModel, haze,
                    requestNotifications = {
                        startActivity(Intent(this@LoginActivity, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            .putExtra("downloads", true))
                    })
                }
            }
        }
    }
    @Composable private fun LoginToolbar() {
        val statuses by WebAccounts.statuses.collectAsState()
        val signedIn = statuses[platform] == AccountStatus.VALID
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            TextButton(onClick = { finish() }) { Text("返回") }
            Text(if(platform == Platform.BILI) "哔哩哔哩登录" else "${platform.label}登录",
                modifier = Modifier.weight(1f).padding(top = 12.dp))
            TextButton(onClick = { message = ""; browser?.reload() }) { Text("刷新") }
            TextButton(onClick = { WebAccounts.flush(); finish() }) { Text("完成") }
        }
        Text(host, modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall)
        Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(if(signedIn) "已登录，点击右上角「完成」返回"
                else if(message.isNotEmpty()) message
                else if(statuses[platform] == AccountStatus.EXPIRED) "登录已失效，请在官网重新登录"
                else "请在官网完成登录，然后点右上角「完成」",
                style = MaterialTheme.typography.bodySmall,
                color = if(message.isNotEmpty() && !signedIn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    @SuppressLint("SetJavaScriptEnabled")
    private fun createBrowser(saved: Bundle?): WebView = WebView(this).apply {
        settings.apply {
            if(platform == Platform.DOUYIN && desktopFallback) userAgentString = VideoResolver.DESKTOP
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_DEFAULT
            // Default mobile UA; do not enable software rendering or clear the resource cache.
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, platform == Platform.WEIBO)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if(!request.isForMainFrame) return false
                if(useDesktopIfRequired(view, request.url.toString())) return true
                if(LoginPolicy.allowedNavigation(platform, request.url.toString())) {
                    message = ""
                    return false
                }
                // Only show the origin: login callbacks can contain credentials in their path/query.
                val origin = "${request.url.scheme.orEmpty()}://${request.url.host.orEmpty()}".take(120)
                message = "已拦截登录页跳转：$origin"
                // A completed login can attempt an app deep link without another page-finished event.
                // Let the official account endpoint decide success, not the navigation outcome.
                WebAccounts.flush()
                return true
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if(LoginPolicy.isFeedMedia(request.url.toString())) {
                    return WebResourceResponse("text/plain", "UTF-8", 204, "No Content", emptyMap(), java.io.ByteArrayInputStream(byteArrayOf()))
                }
                return null
            }
            override fun onPageStarted(view: WebView, url: String, icon: android.graphics.Bitmap?) {
                host = android.net.Uri.parse(url).host.orEmpty()
            }
            override fun onPageCommitVisible(view: WebView, url: String) {
                if(platform == Platform.BILI) view.evaluateJavascript(LoginPageStyle.BILI, null)
            }
            override fun onPageFinished(view: WebView, url: String) {
                if(useDesktopIfRequired(view, url)) return
                if(platform == Platform.BILI) view.evaluateJavascript(LoginPageStyle.BILI, null)
                host = android.net.Uri.parse(url).host.orEmpty()
                if(platform == Platform.YOUTUBE && (url.contains("deniedsignin") || url.contains("disallowed_useragent"))) {
                    message = "Google 不允许在此内嵌网页登录；登录未完成"
                }
                WebAccounts.refresh(true)
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if(request.isForMainFrame) message = "网页加载失败，请检查网络后点刷新"
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { loadingProgress = newProgress }
        }
        val samePlatform = saved?.getString("login_platform") == platform.name
        if(!samePlatform || restoreState(saved!!) == null) loadUrl(startUrl)
    }
    private fun useDesktopIfRequired(view: WebView, url: String): Boolean {
        val uri = android.net.Uri.parse(url)
        if(platform != Platform.DOUYIN || desktopFallback || uri.host != "www.douyin.com" || uri.path != "/home") return false
        // A fresh mobile session can be redirected to the app-download landing page, which has no login.
        desktopFallback = true
        message = "手机版未提供登录入口，已切换电脑版，可双指缩放"
        view.settings.userAgentString = VideoResolver.DESKTOP
        view.loadUrl(startUrl)
        return true
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if(intent.getStringExtra("platform") != platform.name) { setIntent(intent); recreate() }
    }
    override fun onResume() {
        super.onResume()
        browser?.onResume()
        WebAccounts.refresh()
    }
    override fun onPause() {
        browser?.onPause()
        WebAccounts.flush()
        super.onPause()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("login_platform", platform.name)
        outState.putBoolean("desktop_fallback", desktopFallback)
        browser?.saveState(outState)
        super.onSaveInstanceState(outState)
    }
    private fun releaseBrowser(view: WebView) {
        if(browser !== view) return
        browser = null
        view.stopLoading()
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        view.webChromeClient = null
        view.destroy()
    }
    override fun onDestroy() {
        browser?.let(::releaseBrowser)
        super.onDestroy()
    }
}
