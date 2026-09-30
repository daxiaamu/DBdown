package com.daxiaamu.dbdown

import android.os.Build
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay

internal val avoidsSystemIsland: Boolean get() = Build.VERSION.SDK_INT >= 36 ||
    listOf(Build.BRAND,Build.MANUFACTURER).any { it.lowercase() in setOf("oppo","oplus","oneplus","realme") }

/** In-app feedback is visible without notification permission and leaves room for the system island. */
@Composable internal fun BoxScope.ClipboardSuggestionOverlay(vm: MainViewModel, haze: HazeState,
    requestNotifications: () -> Unit = {}) {
    val prompt=vm.clipboardPrompt?.takeUnless { vm.inputVisible || !vm.clipboardEnabled }
    val ready=(prompt as? ClipboardPrompt.Ready)?.info
    val resolving=prompt is ClipboardPrompt.Resolving
    val failed=prompt is ClipboardPrompt.Failed
    val title="发现视频"
    val text=when {
        ready != null -> ready.title
        failed -> "解析失败，请检查网络后重试。"
        else -> "正在解析链接，请稍候…"
    }
    LaunchedEffect(prompt?.key,resolving) {
        if(prompt != null && !resolving) {
            delay(60_000)
            vm.dismissClipboard()
        }
    }
    // Keep the final drag position during the visibility exit animation.
    var swipeKey by remember { mutableStateOf(prompt?.key) }
    if(prompt != null) swipeKey=prompt.key
    val swipe=rememberHeadsUpSwipe(swipeKey) {
        if(vm.clipboardPrompt?.key == prompt?.key) vm.dismissClipboard()
    }
    AnimatedVisibility(prompt != null,
        modifier=Modifier.align(Alignment.TopCenter).statusBarsPadding()
            .padding(top=if(avoidsSystemIsland) 96.dp else 8.dp,start=16.dp,end=16.dp),
        enter=slideInVertically(tween(220)) { -it } + fadeIn(),
        exit=slideOutVertically(tween(180)) { -it } + fadeOut()) {
        GlassPrompt(haze,swipe.widthIn(max=560.dp).fillMaxWidth().testTag("downloadHeadsUp")) {
            Column(Modifier.padding(start=18.dp,end=10.dp,top=10.dp,bottom=8.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    prompt?.let { PlatformIcon(it.link.platform) }
                    if(resolving) AppProgressSpinner(Modifier.padding(start=8.dp).size(16.dp))
                    Text(title,style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f).padding(start=10.dp))
                    IconButton(onClick=vm::dismissClipboard) { Glyph("close","忽略此提示") }
                }
                Text(text,maxLines=2,overflow=TextOverflow.Ellipsis,
                    style=MaterialTheme.typography.bodyMedium,modifier=Modifier.padding(end=8.dp))
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                    TextButton(onClick=vm::dismissClipboard) { Text("忽略") }
                    Button(onClick={ if(failed) vm.retryClipboard() else if(vm.downloadSuggestion()) requestNotifications() },
                        enabled=!resolving && (ready!=null || failed),shape=RoundedCornerShape(14.dp)) {
                        Text(if(failed) "重试" else "下载")
                    }
                }
            }
        }
    }
}
