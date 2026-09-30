package com.daxiaamu.dbdown

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal sealed interface ClipboardPrompt {
    val link: VideoLink
    val timestamp: Long
    val key: String get() = clipboardEventKey(link.key,timestamp)
    data class Resolving(override val link: VideoLink, override val timestamp: Long = 0) : ClipboardPrompt
    data class Ready(override val link: VideoLink, val info: VideoInfo, override val timestamp: Long = 0) : ClipboardPrompt
    data class Failed(override val link: VideoLink, override val timestamp: Long = 0) : ClipboardPrompt
}
internal fun clipboardEventKey(linkKey: String, timestamp: Long) = "clipboard:v2:$timestamp:$linkKey"

/** Local recognition is immediate; asynchronous resolution can only update its own current prompt. */
internal class ClipboardSuggestions(
    private val scope: CoroutineScope,
    private val resolve: suspend (VideoLink) -> VideoInfo,
    private val shouldShow: (String) -> Boolean,
    private val handled: (String) -> Unit
) {
    var state by mutableStateOf<ClipboardPrompt?>(null)
        internal set
    private var currentKey: String? = null
    private var generation=0L
    private var job: Job? = null
    fun inspect(text: String?, timestamp: Long = 0) {
        val link=text?.let(Links::detect)
        if(link == null) { clear(); return }
        val key=clipboardEventKey(link.key,timestamp)
        if(key == currentKey) return
        clear(); currentKey=key
        if(shouldShow(key)) begin(link,timestamp)
    }
    private fun begin(link: VideoLink, timestamp: Long) {
        job?.cancel()
        val request=++generation
        currentKey=clipboardEventKey(link.key,timestamp)
        state=ClipboardPrompt.Resolving(link,timestamp)
        job=scope.launch {
            try {
                val info=resolve(link)
                ensureActive()
                if(request != generation) return@launch
                if(shouldShow(clipboardEventKey(info.id,timestamp))) {
                    state=ClipboardPrompt.Ready(link,info,timestamp)
                    handled(clipboardEventKey(link.key,timestamp))
                    handled(clipboardEventKey(info.id,timestamp))
                } else state=null
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { if(request == generation) state=ClipboardPrompt.Failed(link,timestamp) }
        }
    }
    fun retry() { (state as? ClipboardPrompt.Failed)?.let { begin(it.link,it.timestamp) } }
    fun dismiss() {
        state?.let { prompt ->
            handled(prompt.key)
            (prompt as? ClipboardPrompt.Ready)?.let { handled(clipboardEventKey(it.info.id,it.timestamp)) }
        }
        clear()
    }
    fun clear() { generation++; job?.cancel(); job=null; state=null; currentKey=null }
}
