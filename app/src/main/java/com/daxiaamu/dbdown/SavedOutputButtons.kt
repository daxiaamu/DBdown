package com.daxiaamu.dbdown

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class SavedOutput(val uri: String, val mime: String)
internal fun outputKind(mime: String) = when {
    mime.startsWith("image/") -> "image"
    mime.startsWith("audio/") -> "music"
    else -> "play"
}

@Composable internal fun SavedOutputButtons(task: DownloadTask, notice: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sharing by remember(task.id) { mutableStateOf(false) }
    val outputs by produceState(listOf(SavedOutput(task.uri, task.mimeType)), task.uri, task.outputUris, task.mimeType) {
        value = withContext(Dispatchers.IO) {
            task.outputUris.ifEmpty { listOf(task.uri) }.distinct().map { uri ->
                val mime = runCatching { context.contentResolver.getType(Uri.parse(uri)) }.getOrNull()
                    ?: if(uri == task.uri) task.mimeType else when {
                        "/audio/" in uri -> "audio/*"
                        "/images/" in uri -> "image/*"
                        else -> "application/octet-stream"
                    }
                SavedOutput(uri, mime)
            }
        }
    }
    var selection by remember(task.id) { mutableStateOf<List<SavedOutput>?>(null) }
    fun open(output: SavedOutput) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(output.uri), output.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { notice("无法打开文件，文件可能已移除或没有可用应用") }
    }
    val groups = outputs.groupBy { outputKind(it.mime) }
    val detailed = task.mimeType.startsWith("image/")
    Column(if(detailed) Modifier.fillMaxWidth() else Modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        groups.forEach { (kind, files) ->
            val label = when(kind) { "image" -> "图片"; "music" -> "音乐"; else -> "视频" }
            Row(if(detailed) Modifier.fillMaxWidth() else Modifier,
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if(detailed) {
                    Text(if(kind == "image") "图片 · ${files.size} 张" else label,
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SavedActionButton(onClick = {
                    if(files.size == 1) open(files.first()) else selection = files
                }) {
                    Glyph(kind, if(kind == "image") "打开图片" else "播放$label", Modifier.size(20.dp))
                }
                SavedActionButton(onClick = {
                    sharing = true
                    scope.launch {
                        try {
                            val uris = files.map { Uri.parse(it.uri) }
                            val readable = withContext(Dispatchers.IO) {
                                uris.all { uri -> runCatching {
                                    context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
                                }.getOrDefault(false) }
                            }
                            if(!readable) { notice("无法分享，部分文件可能已移除"); return@launch }
                            val send = Intent(if(uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
                                type = if(files.map { it.mime }.distinct().size == 1) files.first().mime
                                    else when(kind) { "image" -> "image/*"; "music" -> "audio/*"; else -> "video/*" }
                                if(uris.size > 1) putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                                else putExtra(Intent.EXTRA_STREAM, uris.first())
                                putExtra(Intent.EXTRA_TITLE, task.title)
                                clipData = ClipData.newUri(context.contentResolver, task.title, uris.first()).apply {
                                    uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                                }
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            runCatching { context.startActivity(Intent.createChooser(send, "分享$label")) }
                                .onFailure { notice("无法打开系统分享面板，请稍后重试") }
                        } finally { sharing = false }
                    }
                }, enabled = !sharing) { Glyph("share", "分享$label", Modifier.size(20.dp)) }
            }
        }
    }
    selection?.let { files ->
        val label = when(outputKind(files.first().mime)) { "image" -> "图片"; "music" -> "音乐"; else -> "视频" }
        AlertDialog(onDismissRequest = { selection = null }, title = { Text("选择$label") },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    itemsIndexed(files) { index, file ->
                        TextButton(onClick = { selection = null; open(file) }, modifier = Modifier.fillMaxWidth()) {
                            Text("$label ${index + 1}")
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { selection = null }) { Text("关闭") } })
    }
}

@Composable private fun SavedActionButton(onClick: () -> Unit, enabled: Boolean = true,
    content: @Composable () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp),
        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
        content = content)
}
