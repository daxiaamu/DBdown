package com.daxiaamu.dbdown

import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Read after resume even when a dialog owns window focus; never read while paused. */
internal class ForegroundClipboardObserver(
    private val activity: ComponentActivity,
    private val enabled: () -> Boolean,
    private val inspect: (ClipboardContent?) -> Unit
) : DefaultLifecycleObserver {
    private val clipboard = activity.getSystemService(ClipboardManager::class.java)
    private var pending: Job? = null
    private var resumed = false
    private val listener = ClipboardManager.OnPrimaryClipChangedListener { check() }

    override fun onResume(owner: LifecycleOwner) {
        resumed = true
        clipboard.addPrimaryClipChangedListener(listener)
        pending?.cancel()
        pending = activity.lifecycleScope.launch {
            // Android may grant clipboard access slightly after the resume callback.
            for (wait in listOf(0L, 250L, 500L)) {
                delay(wait)
                check()
            }
        }
    }

    fun check() {
        if(resumed && enabled()) inspect(readClipboardContent(activity, excludeSensitive = true))
    }

    override fun onPause(owner: LifecycleOwner) {
        resumed = false
        pending?.cancel()
        clipboard.removePrimaryClipChangedListener(listener)
    }
}
