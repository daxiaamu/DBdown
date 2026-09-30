package com.daxiaamu.dbdown.update

import com.daxiaamu.dbdown.AppProgressSpinner
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daxiaamu.dbdown.DownloaderApp
import com.daxiaamu.dbdown.GlassPrompt
import dev.chrisbanes.haze.HazeState
import java.net.URI
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable fun AboutUpdateCard() {
    val context = LocalContext.current
    val manager = (context.applicationContext as DownloaderApp).updates
    val state by manager.state.collectAsStateWithLifecycle()
    Text("关于", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Surface(shape = RoundedCornerShape(22.dp), modifier = Modifier.testTag("aboutUpdate")) {
        Column {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("逗逼下载器 · DBDown", style = MaterialTheme.typography.titleMedium)
                    Text("版本 ${manager.versionName}", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(Modifier.size(64.dp, 48.dp), contentAlignment = Alignment.Center) {
                    if(state.checking) AppProgressSpinner(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else TextButton(onClick = { manager.check(true) }, modifier = Modifier.fillMaxSize().testTag("checkUpdate"),
                        contentPadding = PaddingValues(0.dp)) { Text("检查更新", fontSize = 13.sp) }
                    if(state.redDot) Box(Modifier.align(Alignment.TopEnd).padding(top = 3.dp).size(6.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape))
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Surface(onClick = {
                runCatching {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://github.com/${com.daxiaamu.dbdown.BuildConfig.UPDATE_REPOSITORY}")))
                }.onFailure { Toast.makeText(context, "没有可以打开项目页的应用", Toast.LENGTH_SHORT).show() }
            }, modifier = Modifier.testTag("openSource")) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("开放源代码", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    com.daxiaamu.dbdown.Glyph("arrow")
                }
            }
        }
    }
}

@Composable fun UpdateOverlay(manager: UpdateManager = (LocalContext.current.applicationContext as DownloaderApp).updates,
    haze: HazeState = remember { HazeState() }) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity ?: return
    val state by manager.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.message) {
        state.message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); manager.consumeMessage() }
    }
    val manifest = state.manifest ?: return
    if(!state.dialog) return
    val required = manager.isRequired(manifest)
    val dismissible = !required && !state.busy && state.stage != UpdateStage.AUTHORIZATION
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    Dialog(onDismissRequest = { if(dismissible) manager.dismiss(false) },
        properties = DialogProperties(dismissOnBackPress = dismissible, dismissOnClickOutside = dismissible,
            usePlatformDefaultWidth = false)) {
        GlassPrompt(haze, Modifier.padding(horizontal = 16.dp).widthIn(max = 520.dp).fillMaxWidth().testTag("updateDialog"),
            shape = RoundedCornerShape(28.dp)) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.padding(20.dp)) {
                SelectionContainer(Modifier.fillMaxWidth().height((screenHeight * .50f).coerceAtMost(440.dp))) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if(required) "需要更新" else "发现新版本", style = MaterialTheme.typography.titleLarge)
                        Text(manifest.versionName, style = MaterialTheme.typography.titleMedium)
                        manifest.publishedAt?.let { instant ->
                            val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
                            val formatted = remember(instant, locale, ZoneId.systemDefault()) {
                                DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                                    .withLocale(locale).withZone(ZoneId.systemDefault()).format(instant)
                            }
                            Text("发布时间：$formatted", style = MaterialTheme.typography.bodyMedium)
                        }
                        if(required) Text("此版本需要升级后继续使用。", style = MaterialTheme.typography.bodyMedium)
                        SafeMarkdown(manifest.changelog.ifBlank { "此版本包含体验改进和问题修复。" })
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = { manager.install(activity) }, enabled = !state.checking && !state.busy && state.stage != UpdateStage.AUTHORIZATION,
                        modifier = Modifier.width(160.dp).height(48.dp)) {
                        if(state.busy) {
                            if(state.stage == UpdateStage.DOWNLOADING && state.progress != null)
                                AppProgressSpinner(progress = { state.progress!!.coerceAtMost(.99f) },
                                    modifier = Modifier.size(20.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
                            else AppProgressSpinner(Modifier.size(20.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(when(state.stage) {
                            UpdateStage.DOWNLOADING -> state.progress?.let { "${(it.coerceAtMost(.99f)*100).toInt()}%" } ?: "下载中"
                            UpdateStage.VERIFYING -> "校验中"
                            UpdateStage.READY -> "安装"
                            UpdateStage.AUTHORIZATION -> "等待授权"
                            else -> if(state.error == null) "下载安装" else "重试下载"
                        })
                    }
                }
                if(!required) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { manager.dismiss(true) }, enabled = dismissible) { Text("跳过此版本") }
                    TextButton(onClick = { manager.dismiss(false) }, enabled = dismissible) { Text("忽略") }
                }
            }
            }
        }
    }
}

@Composable private fun SafeMarkdown(markdown: String) {
    val lines = remember(markdown) { markdown.lines() }
    var code = false
    for(line in lines) {
        if(line.trimStart().startsWith("~~~") || line.trimStart().startsWith("\u0060\u0060\u0060")) { code = !code; continue }
        if(code) {
            Text(line, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(8.dp))
            continue
        }
        if(line.trim() in listOf("---", "***", "___")) { HorizontalDivider(); continue }
        val heading = Regex("^(#{1,6})\\s+(.*)$").matchEntire(line)
        val quote = line.trimStart().startsWith("> ")
        val text = when {
            heading != null -> heading.groupValues[2]
            quote -> "│ " + line.trimStart().removePrefix("> ")
            Regex("^\\s*[-*+]\\s+").containsMatchIn(line) -> line.replaceFirst(Regex("^\\s*[-*+]\\s+"), "• ")
            else -> line
        }
        Text(inlineMarkdown(text, MaterialTheme.colorScheme.primary),
            style = if(heading != null) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium)
    }
}
internal fun inlineMarkdown(text: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    val safe = text.replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "$1")
    val pattern = Regex("\\[([^]]+)]\\((https?://[^\\s)]+)\\)|\\*\\*([^*]+)\\*\\*|__([^_]+)__|(?<!\\*)\\*([^*]+)\\*(?!\\*)|(?<!_)_([^_]+)_(?!_)|\u0060([^\u0060]+)\u0060")
    var cursor = 0
    for(match in pattern.findAll(safe)) {
        append(safe.substring(cursor, match.range.first))
        when {
            match.groupValues[2].isNotEmpty() -> {
                val url = match.groupValues[2]
                val valid = runCatching { val uri = URI(url); uri.host != null && uri.userInfo == null }.getOrDefault(false)
                if(valid) withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = linkColor)))) { append(match.groupValues[1]) }
                else append(match.value)
            }
            match.groupValues[3].isNotEmpty() || match.groupValues[4].isNotEmpty() ->
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(match.groupValues[3].ifEmpty { match.groupValues[4] }) }
            match.groupValues[7].isNotEmpty() ->
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(match.groupValues[7]) }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(match.groupValues[5].ifEmpty { match.groupValues[6] }) }
        }
        cursor = match.range.last + 1
    }
    append(safe.substring(cursor))
}
