package com.daxiaamu.dbdown

import android.content.ClipboardManager
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Observe only while visible and resumed; the clipboard text is read only after the user's tap. */
@Composable internal fun HomePasteButton(visible: Boolean, paste: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var available by remember { mutableStateOf(false) }
    DisposableEffect(owner, visible) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        fun refresh() {
            available = visible && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && runCatching {
                clipboard.hasPrimaryClip() && clipboard.primaryClipDescription?.hasMimeType("text/*") == true
            }.getOrDefault(false)
        }
        val listener = ClipboardManager.OnPrimaryClipChangedListener { refresh() }
        var registered = false
        fun update() {
            val active = visible && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if(active && !registered) { clipboard.addPrimaryClipChangedListener(listener); registered = true }
            if(!active && registered) { clipboard.removePrimaryClipChangedListener(listener); registered = false }
            refresh()
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        owner.lifecycle.addObserver(observer)
        update()
        onDispose { owner.lifecycle.removeObserver(observer); if(registered) clipboard.removePrimaryClipChangedListener(listener) }
    }
    if(available) TextButton(onClick = {
        val text = readClipboardText(context, excludeSensitive = false).orEmpty()
        if(text.isNotEmpty()) paste(text) else available = false
    }, modifier = Modifier.testTag("homePasteButton")) { Text("粘贴") }
    else Glyph("arrow", tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
}
