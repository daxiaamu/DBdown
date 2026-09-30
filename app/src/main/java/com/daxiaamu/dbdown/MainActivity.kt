package com.daxiaamu.dbdown

import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val model by viewModels<MainViewModel>()
    private val clipboardObserver by lazy { ForegroundClipboardObserver(this, { model.clipboardEnabled && !model.inputVisible }) { text ->
        if(model.clipboardEnabled && !model.inputVisible) model.inspectClipboard(text)
    } }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycle.addObserver(clipboardObserver)
        if(savedInstanceState == null) handleIntent(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                delay(2000)
                (application as DownloaderApp).updates.check(false)
                awaitCancellation()
            }
        }
        setContent { DownloaderTheme { DownloaderScreen(model, ::requestNotifications, ::checkClipboard) } }
    }
    private fun requestNotifications() {
        if(ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            val prefs = getSharedPreferences("settings", 0)
            if(!prefs.getBoolean("notificationAsked", false)) {
                prefs.edit().putBoolean("notificationAsked", true).apply()
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent); handleIntent(intent)
    }
    private fun handleIntent(intent: Intent?) {
        if(intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            model.onShare(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
        } else if(intent?.getBooleanExtra("downloads", false) == true) model.tab = 1
    }
    override fun onResume() {
        super.onResume()
        WebAccounts.refresh()
        window.decorView.post { checkClipboard(); (application as DownloaderApp).updates.onResume(this) }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if(hasFocus) window.decorView.post { checkClipboard() }
    }
    private fun checkClipboard() { clipboardObserver.check() }
}
