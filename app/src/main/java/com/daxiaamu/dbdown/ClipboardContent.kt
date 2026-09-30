package com.daxiaamu.dbdown

internal data class ClipboardContent(val text: String, val timestamp: Long)

internal fun readClipboardContent(context: android.content.Context, excludeSensitive: Boolean): ClipboardContent? = runCatching {
    val clip = context.getSystemService(android.content.ClipboardManager::class.java).primaryClip ?: return@runCatching null
    if(excludeSensitive && clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true) return@runCatching null
    if(clip.itemCount == 0) null else clip.getItemAt(0).text?.toString()?.take(16000)?.let { ClipboardContent(it, clip.description.timestamp) }
}.getOrNull()

internal fun readClipboardText(context: android.content.Context, excludeSensitive: Boolean): String? =
    readClipboardContent(context, excludeSensitive)?.text
