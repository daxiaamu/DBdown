package com.daxiaamu.dbdown

import dev.chrisbanes.haze.HazeProgressive
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.core.FastOutSlowInEasing
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.hazeEffect
import androidx.compose.ui.unit.Dp
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Day = lightColorScheme(
    primary = Color(0xFF315CDE), onPrimary = Color.White, primaryContainer = Color(0xFFE9EEFF),
    onPrimaryContainer = Color(0xFF2245AD), background = Color(0xFFF7F8FA),
    surface = Color.White, surfaceContainer = Color(0xFFEEF0F5),
    onSurface = Color(0xFF202631), onSurfaceVariant = Color(0xFF646E7F),
    outlineVariant = Color(0xFFE2E6EE), error = Color(0xFFB93840)
)
private val Night = darkColorScheme(
    primary = Color(0xFFB4C5FF), onPrimary = Color(0xFF163787), primaryContainer = Color(0xFF253B73),
    onPrimaryContainer = Color(0xFFDCE4FF), background = Color(0xFF11151D),
    surface = Color(0xFF1C222D), surfaceContainer = Color(0xFF252C39),
    onSurface = Color(0xFFE5E9F1), onSurfaceVariant = Color(0xFFA7B0C1),
    outlineVariant = Color(0xFF343F50), error = Color(0xFFFFB3B8)
)
@Composable fun DownloaderTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if(isSystemInDarkTheme()) Night else Day, typography = Typography(
        headlineMedium = androidx.compose.ui.text.TextStyle(fontSize = 27.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = androidx.compose.ui.text.TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
        bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
        labelLarge = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)
    ), content = content)
}
private val paths = mapOf(
    "completed" to "M22,12 A10,10 0,1 1,2,12 A10,10 0,1 1,22,12 M7,12 L10.5,15.5 L17,8.5",
    "clock" to "M22,12 A10,10 0,1 1,2,12 A10,10 0,1 1,22,12 M12,6 L12,12 L16,14",
    "search" to "M17,10 A7,7 0,1 1,3,10 A7,7 0,1 1,17,10 M15,15 L21,21",
    "layers" to "M3,8 L12,3 L21,8 L12,13 Z M3,12 L12,17 L21,12 M3,16 L12,21 L21,16",
    "error" to "M22,12 A10,10 0,1 1,2,12 A10,10 0,1 1,22,12 M12,6 L12,13 M12,17 L12,18",
    "home" to "M3,10 L12,3 L21,10 L21,21 L15,21 L15,14 L9,14 L9,21 L3,21 Z",
    "download" to "M12,3 L12,15 M6,9 L12,15 L18,9 M4,16 L4,21 L20,21 L20,16",
    "link" to "M10,13 L14,9 M8,16 L6,18 C2,22 -2,16 2,12 L6,8 C8,6 11,6 13,8 M11,16 C13,18 16,18 18,16 L22,12 C26,8 20,2 16,6 L14,8",
    "close" to "M6,6 L18,18 M18,6 L6,18",
    "back" to "M15,5 L8,12 L15,19",
    "check" to "M5,12 L10,17 L20,7",
    "share" to "M8.5,10.5 L15.5,6.5 M8.5,13.5 L15.5,17.5 M9,12 A3,3 0,1 1,3,12 A3,3 0,1 1,9,12 M21,5 A3,3 0,1 1,15,5 A3,3 0,1 1,21,5 M21,19 A3,3 0,1 1,15,19 A3,3 0,1 1,21,19",
    "pause" to "M8,5 L8,19 M16,5 L16,19",
    "play" to "M8,5 L19,12 L8,19 Z",
    "retry" to "M4,10 A8,8 0,1 1,5,18 M4,4 L4,10 L10,10",
    "more" to "M12,4 L12,5 M12,11 L12,12 M12,18 L12,19",
    "clipboard" to "M9,5 L5,5 L5,21 L19,21 L19,5 L15,5 M9,3 L15,3 L15,7 L9,7 Z M9,12 L15,12 M9,16 L13,16",
    "arrow" to "M5,12 L19,12 M13,6 L19,12 L13,18"
)
@Composable internal fun Glyph(name: String, description: String? = null, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    if(name == "settings" || name == "trash") {
        Icon(androidx.compose.ui.res.painterResource(if(name == "settings") R.drawable.ic_settings else R.drawable.ic_delete), contentDescription = description,
            modifier = modifier.size(22.dp), tint = tint)
        return
    }
    val vector = remember(name) {
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            addPath(PathParser().parsePathString(paths.getValue(name)).toNodes(),
                stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
                strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
                strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round)
        }.build()
    }
    Icon(vector, contentDescription = description, modifier = modifier.size(22.dp), tint = tint)
}

@Composable private fun appGlassStyle() = HazeStyle(
    backgroundColor = MaterialTheme.colorScheme.background,
    tint = HazeTint(MaterialTheme.colorScheme.background.copy(alpha = 0.46f)),
    blurRadius = 14.dp, noiseFactor = 0f
)

@Composable internal fun GlassPrompt(haze: HazeState, modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(24.dp), content: @Composable () -> Unit) {
    Box(modifier.shadow(4.dp, shape).clip(shape)) {
        // Blur only the backdrop. Drawing Surface elevation after haze creates a shadow inside the glass.
        Box(Modifier.matchParentSize().hazeEffect(haze, style = appGlassStyle()))
        content()
    }
}

@Composable fun DownloaderScreen(vm: MainViewModel, requestNotifications: () -> Unit, checkClipboard: () -> Unit) {
    AccountExpiryPrompt { vm.inputVisible = false; vm.settings = true }
    val messagesLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm, messagesLifecycle) {
        messagesLifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            while(true) { vm.refreshHomeMessages(); delay(300_000) }
        }
    }
    val tasks by vm.store.tasks.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haze = remember { HazeState() }
    val dialogHaze = remember { HazeState() }
    val updateHaze = remember { HazeState() }
    val updateManager = (context.applicationContext as DownloaderApp).updates
    val updateState by updateManager.state.collectAsStateWithLifecycle()
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 72.dp
    val blurFadeStart = with(LocalDensity.current) { (topInset - 20.dp).toPx() }
    val blurFadeEnd = with(LocalDensity.current) { (topInset + 24.dp).toPx() }
    val pager = rememberPagerState(initialPage = vm.tab) { 2 }
    LaunchedEffect(vm.tabRequest) {
        if(vm.tabRequest != vm.consumedTabRequest) {
            vm.consumedTabRequest = vm.tabRequest
            pager.animateScrollToPage(vm.requestedTab)
        }
    }
    LaunchedEffect(pager) {
        snapshotFlow { if(pager.isScrollInProgress) null else pager.settledPage }
            .collect { page -> if(page != null) vm.onPageSettled(page) }
    }
    BackHandler(vm.settings || pager.currentPage != 0) { if(vm.settings) vm.settings = false else vm.tab = 0 }
    LaunchedEffect(vm.notice) {
        vm.notice?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.notice = null }
    }
    Surface(Modifier.fillMaxSize().then(if(updateState.dialog) Modifier.hazeSource(updateHaze) else Modifier),
        color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))) {
            Box(Modifier.fillMaxSize().hazeSource(haze)
                .then(if(vm.inputVisible) Modifier.hazeSource(dialogHaze) else Modifier)) {
                if(vm.settings) {
                    SettingsPage(vm, topInset) { enabled -> vm.setClipboard(enabled); if(enabled) checkClipboard() }
                } else HorizontalPager(
                    state = pager, modifier = Modifier.fillMaxSize().testTag("pages"),
                    beyondViewportPageCount = 1, key = { it }
                ) { page ->
                    if(page == 0) {
                    Box(Modifier.fillMaxSize().testTag("homePage").padding(top = topInset).padding(horizontal = 28.dp).padding(bottom = 120.dp), contentAlignment = Alignment.Center) {
                        Column(Modifier.widthIn(max = 540.dp).fillMaxWidth()) {
                        HomeMessageCarousel(vm.homeMessages, pager.currentPage == 0 && !vm.inputVisible)
                        Surface(onClick = { vm.openInput() }, modifier = Modifier.widthIn(max = 540.dp).fillMaxWidth().height(66.dp),
                            shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 2.dp) {
                            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Glyph("link", tint = MaterialTheme.colorScheme.primary)
                                Text("粘贴视频链接", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                Glyph("arrow", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        }
                    }
                } else DownloadsPage(tasks, vm, requestNotifications, topInset)
                }
            }
            // Extend just the backdrop; layout and touch targets keep their original bounds.
            Box(Modifier.fillMaxWidth().height(topInset + 24.dp).hazeEffect(haze, style = appGlassStyle()) {
                progressive = HazeProgressive.verticalGradient(
                    startY = blurFadeStart, startIntensity = 1f,
                    endY = blurFadeEnd, endIntensity = 0f,
                    easing = FastOutSlowInEasing
                )
            })
                Row(Modifier.fillMaxWidth().statusBarsPadding().height(72.dp).padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if(vm.settings) {
                        IconButton(onClick = { vm.settings = false }) { Glyph("back", "返回") }
                        Text("设置", style = MaterialTheme.typography.titleLarge)
                    } else {
                        Text(if(pager.currentPage == 0) androidx.compose.ui.res.stringResource(R.string.app_name) else "下载", style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f))
                        if(pager.currentPage == 1) {
                            IconButton(onClick = { vm.requestDelete(tasks.map { it.id }, all = true) },
                                enabled = !vm.deleting && tasks.isNotEmpty(), modifier = Modifier.testTag("clearDownloads")) {
                                Glyph("trash", "清空下载记录")
                            }
                        }
                        IconButton(onClick = { vm.settings = true }) { Glyph("settings", "设置") }
                    }
                }
            if(!vm.settings) {
                FloatingTabs(pager, haze,
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp)) { vm.tab = it }
            }
            ClipboardSuggestionOverlay(vm, haze, requestNotifications)
        }
    }
    if(vm.inputVisible) LinkDialog(vm, dialogHaze) { if(vm.submit()) requestNotifications() }
    DeleteTasksDialog(vm)
    com.daxiaamu.dbdown.update.UpdateOverlay(updateManager, updateHaze)
}


@Composable internal fun BoxScope.ClipboardSuggestionOverlay(vm: MainViewModel, haze: HazeState,
    requestNotifications: () -> Unit = {}, openInput: (String) -> Unit = vm::openInput) {
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 72.dp
    val suggestion = vm.clipboardSuggestion?.takeUnless { vm.inputVisible || !vm.clipboardEnabled }
    ClipboardPromptEffect(suggestion?.id,
        "发现${suggestion?.source?.platform?.label.orEmpty()}${if(suggestion?.images?.isNotEmpty() == true) "图集" else "视频"}",
        suggestion?.title.orEmpty(), "下载作品",
        buildList {
            add(ClipboardPromptAction(if(suggestion?.images?.isNotEmpty() == true) "保存图片" else "下载") {
                if(vm.downloadSuggestion()) requestNotifications()
            })
            if(suggestion?.images?.isNotEmpty() == true && !suggestion.music.isNullOrBlank() && !suggestion.separateAlbumMusic) add(ClipboardPromptAction("合成视频") {
                if(vm.downloadSuggestion(AlbumMode.VIDEO)) requestNotifications()
            })
        }, open = { suggestion?.let { openInput(it.source.url) } }, dismiss = { vm.clipboardSuggestion = null })
            AnimatedVisibility(suggestion != null,
                modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 16.dp).padding(top = topInset - 72.dp + if(ClipboardLivePrompt.avoidSystemIsland) 96.dp else 8.dp),
                enter = slideInVertically(tween(220)) { -it } + fadeIn(),
                exit = slideOutVertically(tween(180)) { -it } + fadeOut()) {
                vm.clipboardSuggestion?.let { info ->
                    GlassPrompt(haze, Modifier.widthIn(max = 560.dp).fillMaxWidth().testTag("downloadHeadsUp")) {
                        Column(Modifier.padding(start = 18.dp, end = 10.dp, top = 10.dp, bottom = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Glyph("clipboard", tint = MaterialTheme.colorScheme.primary)
                                Text("发现${info.source.platform.label}${if(info.images.isEmpty()) "视频" else "图集 · ${info.images.size} 张"}", style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f).padding(start = 10.dp))
                                IconButton(onClick = { vm.clipboardSuggestion = null }) { Glyph("close", "忽略此视频") }
                            }
                            Text(info.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { vm.clipboardSuggestion = null }) { Text("忽略") }
                                if(info.images.isNotEmpty() && !info.music.isNullOrBlank() && !info.separateAlbumMusic) TextButton(onClick = { if(vm.downloadSuggestion(AlbumMode.VIDEO)) requestNotifications() }) { Text("合成视频") }
                                Button(onClick = { if(vm.downloadSuggestion()) requestNotifications() }, shape = RoundedCornerShape(14.dp)) {
                                    Text(if(info.images.isEmpty()) "下载" else "保存图片")
                                }
                            }
                        }
                    }
                }
            }
}

@Composable private fun LinkDialog(vm: MainViewModel, haze: HazeState, submit: () -> Unit) {
    val context = LocalContext.current
    val detected = remember(vm.input) { Links.detect(vm.input) }
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var candidate by remember { mutableStateOf<String?>(null) }
    var inspected by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = { vm.inputVisible = false }, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val windowFocused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(focused, windowFocused, vm.clipboardEnabled) {
            if(!windowFocused || !vm.clipboardEnabled) return@LaunchedEffect
            val text = readClipboardText(context, excludeSensitive = true) ?: return@LaunchedEffect
            if(text == inspected) return@LaunchedEffect
            inspected = text
            val link = Links.detect(text)
            candidate = text.takeIf { link != null && link.key != Links.detect(vm.input)?.key }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
        LaunchedEffect(vm.clipboardEnabled) { if(!vm.clipboardEnabled) candidate = null }
        ClipboardPromptEffect(candidate,
            "剪贴板中有${candidate?.let(Links::detect)?.platform?.label.orEmpty()}链接", "是否填入下载输入框？", "填入链接",
            listOf(ClipboardPromptAction("填入") { candidate?.let { vm.input = it; vm.error = null }; candidate = null }),
            open = { candidate?.let { vm.input = it; vm.error = null }; candidate = null }, dismiss = { candidate = null })
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp, modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("添加下载", style = MaterialTheme.typography.titleLarge)
                        AnimatedVisibility(candidate != null, enter = slideInVertically { -it } + fadeIn(), exit = fadeOut()) {
                            candidate?.let { text ->
                                GlassPrompt(haze, Modifier.widthIn(max = 560.dp).fillMaxWidth().testTag("inputClipboardHeadsUp")) {
                                    Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Glyph("clipboard", tint = MaterialTheme.colorScheme.primary)
                                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                            Text("${Links.detect(text)?.platform?.label.orEmpty()}链接", style = MaterialTheme.typography.titleMedium)
                                            Text("填入剪贴板链接？", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        TextButton(onClick = { vm.input = text; vm.error = null; candidate = null }) { Text("填入") }
                                        IconButton(onClick = { candidate = null }, modifier = Modifier.size(40.dp)) { Glyph("close", "忽略剪贴板链接") }
                                    }
                                }
                            }
                        }
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("粘贴链接或完整分享文案", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedTextField(value = vm.input, onValueChange = { vm.input = it.take(16000); vm.error = null; candidate = null },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp, max = 180.dp)
                                    .testTag("downloadLinkInput").focusRequester(focus).onFocusChanged { focused = it.isFocused },
                                placeholder = { Text("B 站 / 抖音 / YouTube 链接或视频 ID") }, shape = RoundedCornerShape(16.dp),
                                trailingIcon = if(vm.input.isNotEmpty()) {{ IconButton(onClick = {
                                    vm.input = ""; vm.error = null; candidate = null; focus.requestFocus()
                                }, modifier = Modifier.testTag("clearLinkInput")) { Glyph("close", "清空输入") } }} else null,
                                isError = vm.error != null, maxLines = 5)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(if(detected != null) "已识别：${detected.platform.label}链接" else "自动识别视频或图集",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if(detected != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    vm.input = readClipboardText(context, excludeSensitive = false).orEmpty()
                                    vm.error = null; candidate = null
                                }) { Text("粘贴") }
                            }
                            if(detected?.platform == Platform.DOUYIN) {
                                Text("如果是图集，保存为", style = MaterialTheme.typography.bodyMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(selected = vm.albumMode == AlbumMode.IMAGES, onClick = { vm.albumMode = AlbumMode.IMAGES }, label = { Text("图片") })
                                    FilterChip(selected = vm.albumMode == AlbumMode.VIDEO, onClick = { vm.albumMode = AlbumMode.VIDEO }, label = { Text("视频") })
                                }
                                if(vm.albumMode == AlbumMode.VIDEO) Text("按完整配乐时长平均展示每张图片；无配乐时仅保存图片", style = MaterialTheme.typography.bodySmall)
                            }
                            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                            TextButton(onClick = { vm.inputVisible = false }) { Text("取消") }
                            Button(onClick = submit, enabled = vm.input.isNotBlank(), shape = RoundedCornerShape(14.dp)) { Text("下载") }
                        }
                    }
                }
            }
        }
    }
}

internal fun readClipboardText(context: android.content.Context, excludeSensitive: Boolean): String? = runCatching {
    val clip = context.getSystemService(android.content.ClipboardManager::class.java).primaryClip ?: return@runCatching null
    if(excludeSensitive && clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true) return@runCatching null
    if(clip.itemCount == 0) null else clip.getItemAt(0).text?.toString()?.take(16000)
}.getOrNull()

@Composable private fun DownloadsPage(tasks: List<DownloadTask>, vm: MainViewModel, requestNotifications: () -> Unit, topInset: Dp) {
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val revealTaskId = vm.revealTaskId
    LaunchedEffect(revealTaskId) {
        if(revealTaskId != null) {
            filter = 0
            withFrameNanos { }
            listState.scrollToItem(0)
            vm.taskRevealed(revealTaskId)
        }
    }
    val paused by vm.store.paused.collectAsStateWithLifecycle()
    val filtered = when(filter) {
        1 -> tasks.filter { it.status.pending }
        2 -> tasks.filter { it.status == TaskStatus.COMPLETED }
        else -> tasks
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("downloadList"),
        contentPadding = PaddingValues(top = topInset, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if(tasks.any { it.status.pending }) item(key = "queueControls") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                FilledTonalIconButton(onClick = {
                    if(paused) { vm.resumeDownloads(); requestNotifications() } else vm.pauseDownloads()
                }, enabled = !vm.deleting, modifier = Modifier.size(48.dp).testTag("queueControl")) {
                    Glyph(if(paused) "play" else "pause", if(paused) "全部开始" else "全部暂停")
                }
            }
        }
        item(key = "filters") { Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("全部", "进行中", "已完成").forEachIndexed { index, title ->
                FilterChip(selected = filter == index, onClick = { filter = index }, label = { Text(title) }, shape = CircleShape)
            }
        }
        }
        if(filtered.isEmpty()) { item(key = "empty") {
            Column(Modifier.fillParentMaxHeight(0.65f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
                        Glyph("download", modifier = Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text(when(filter) { 1 -> "没有正在下载的作品"; 2 -> "还没有下载完成的作品"; else -> "下载的作品会出现在这里" },
                    style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("从 B 站、抖音或 YouTube 分享视频开始", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = { vm.openInput() }) { Text("添加链接") }
            }
        } } else items(filtered, key = { it.id }) { task ->
            Box(Modifier.padding(horizontal = 24.dp)) { DownloadCard(task, vm, requestNotifications) }
        }
    }
}
@Composable private fun DownloadCard(task: DownloadTask, vm: MainViewModel, requestNotifications: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sharing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(task.uri, task.status) {
        if(task.status == TaskStatus.COMPLETED && task.uri.isNotBlank()) {
            val measured = withContext(Dispatchers.IO) { savedResolution(context, task) }
            if(measured.isNotEmpty() && measured != task.resolution) vm.store.update(task.id) {
                if(it.uri == task.uri && it.status == TaskStatus.COMPLETED) it.copy(resolution = measured) else it
            }
        }
    }
    Surface(modifier = Modifier.testTag("download-${task.id}"), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PlatformIcon(task.platform)
                        Text(if(task.quality.contains("张图片")) task.quality else "",
                            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        Text(when(task.status) {
                                TaskStatus.DOWNLOADING -> "${formatBytes(task.bytes)} / ${if(task.total > 0) formatBytes(task.total) else "未知"}"
                                TaskStatus.COMPLETED -> formatBytes(task.bytes)
                                else -> task.status.label
                            },
                            maxLines = 1, style = MaterialTheme.typography.labelMedium,
                            fontFamily = if(task.status in setOf(TaskStatus.DOWNLOADING, TaskStatus.COMPLETED)) FontFamily.Monospace else FontFamily.Default,
                            color = if(task.status == TaskStatus.FAILED || task.status == TaskStatus.INTERRUPTED)
                                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(task.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Box {
                    IconButton(onClick = { menu = true }, enabled = !vm.deleting, modifier = Modifier.size(36.dp)) { Glyph("more", "更多操作") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("查看原作品") }, onClick = {
                            menu = false
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(task.source))) }
                                .onFailure { vm.notice = "没有可以打开链接的应用" }
                        })
                        DropdownMenuItem(text = { Text("删除任务") }, onClick = {
                            menu = false; vm.requestDelete(listOf(task.id), withFiles = false)
                        })
                        DropdownMenuItem(text = { Text("删除任务和文件") }, onClick = {
                            menu = false; vm.requestDelete(listOf(task.id), withFiles = true)
                        })
                    }
                }
            }
            if(task.status.pending) {
                if(task.status == TaskStatus.PAUSED || (task.total > 0 && task.status == TaskStatus.DOWNLOADING)) {
                    LinearProgressIndicator(progress = { task.progress }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape))
                } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape))
            }
            if(task.error.isNotEmpty()) Text(task.error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(task.resolution.ifBlank { task.quality.takeIf { it.matches(Regex("[0-9]+P")) } ?: "—" },
                        style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if(task.status == TaskStatus.DOWNLOADING) {
                        Text(buildAnnotatedString {
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                                append("${formatBytes(task.speed)}/s")
                            }
                            if(task.total > 0) append(" · ${(task.progress*100).toInt()}%")
                        },
                            style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if(task.status.pending) {
                    TextButton(onClick = { vm.cancel(task.id) }, enabled = !vm.deleting) { Text("取消") }
                } else if(task.status == TaskStatus.COMPLETED) {
                    FilledTonalIconButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(task.uri), task.mimeType)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                            .onFailure { vm.notice = "无法打开文件，文件可能已移除或没有可用应用" }
                    }, modifier = Modifier.size(48.dp)) { Glyph("play", if(task.mimeType.startsWith("image/")) "打开图片" else "打开视频") }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalIconButton(onClick = {
                        sharing = true
                        scope.launch {
                            try {
                                val uris = (task.outputUris.ifEmpty { listOf(task.uri) }).map(Uri::parse)
                                val readable = withContext(Dispatchers.IO) {
                                    uris.all { uri -> runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false) }
                                }
                                if(!readable) { vm.notice = "无法分享，部分文件可能已移除"; return@launch }
                                val send = Intent(if(uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
                                    val types = uris.mapNotNull { context.contentResolver.getType(it) }.distinct()
                                    type = if(types.size > 1) "*/*" else types.firstOrNull() ?: task.mimeType
                                    if(uris.size > 1) putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                                    else putExtra(Intent.EXTRA_STREAM, uris.first())
                                    putExtra(Intent.EXTRA_TITLE, task.title)
                                    clipData = ClipData.newUri(context.contentResolver, task.title, uris.first()).apply {
                                        uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                                    }
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                runCatching { context.startActivity(Intent.createChooser(send, if(task.mimeType.startsWith("image/")) "分享图片" else "分享视频")) }
                                    .onFailure { vm.notice = "无法打开系统分享面板，请稍后重试" }
                            } finally { sharing = false }
                        }
                    }, enabled = !sharing, modifier = Modifier.size(48.dp)) { Glyph("share", if(task.mimeType.startsWith("image/")) "分享图片" else "分享视频") }
                } else {
                    TextButton(onClick = { vm.retry(task.id); requestNotifications() }, enabled = !vm.deleting) { Text("重试") }
                }
            }
        }
    }
}
@Composable private fun DeleteTasksDialog(vm: MainViewModel) {
    val request = vm.deleteRequest ?: return
    val files = request.withFiles == true
    val choice = request.withFiles == null
    val count = request.ids.size
    AlertDialog(onDismissRequest = vm::dismissDeletion, shape = RoundedCornerShape(28.dp),
        title = { Text(if(choice) "清空下载记录" else if(files) "删除任务和文件？" else "删除任务？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if(choice) "将清空 $count 个下载任务，请选择是否保留已下载文件。"
                    else if(files) "将删除 $count 个任务，以及这些任务保存的所有视频和图片。文件删除后无法恢复。"
                    else "将删除 $count 个任务，已保存的视频和图片会保留在相册中。")
                Text("未完成的下载会停止，临时文件会清理。", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if(choice) {
                    OutlinedButton(onClick = { vm.confirmDeletion(false) }, enabled = !vm.deleting, modifier = Modifier.fillMaxWidth()) { Text("仅删除任务") }
                    OutlinedButton(onClick = vm::chooseDeleteFiles, enabled = !vm.deleting, modifier = Modifier.fillMaxWidth()) { Text("删除任务和文件") }
                }
                if(vm.deleting) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }, confirmButton = {
            if(!choice) Button(onClick = { vm.confirmDeletion(files) }, enabled = !vm.deleting,
                colors = if(files) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
                modifier = Modifier.testTag("confirmDeleteTasks")) { Text(if(files) "确认删除文件" else "删除任务") }
        }, dismissButton = { TextButton(onClick = vm::dismissDeletion, enabled = !vm.deleting) { Text("取消") } })
}

@Composable private fun SettingsPage(vm: MainViewModel, topInset: Dp, onClipboard: (Boolean) -> Unit) {
    val context = LocalContext.current
    val parallelism by vm.store.parallelism.collectAsStateWithLifecycle()
    var selectParallelism by remember { mutableStateOf(false) }
    if(selectParallelism) AlertDialog(onDismissRequest = { selectParallelism = false },
        title = { Text("最多同时下载") },
        text = { Column {
            (1..6).forEach { count ->
                Row(Modifier.fillMaxWidth().selectable(selected = parallelism == count,
                    role = androidx.compose.ui.semantics.Role.RadioButton,
                    onClick = { vm.setParallelism(count); selectParallelism = false }).padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = parallelism == count, onClick = null)
                    Text("$count 个任务" + if(count == 3) "（默认）" else "", Modifier.padding(start = 12.dp))
                }
            }
        } }, confirmButton = {}, dismissButton = { TextButton(onClick = { selectParallelism = false }) { Text("取消") } })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = topInset).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text("下载", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(onClick = { selectParallelism = true }, shape = RoundedCornerShape(22.dp),
            modifier = Modifier.testTag("parallelismSetting")) {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("最多同时下载", style = MaterialTheme.typography.titleMedium)
                    Text("$parallelism 个任务", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                    Text("调低后，进行中的任务继续完成，后续按新上限开始。", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Glyph("arrow")
            }
        }
        AccountSettings()
        Text("链接与提醒", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("自动检测剪贴板", style = MaterialTheme.typography.titleMedium)
                        Text("打开应用或回到前台时，识别视频并提示下载。", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = vm.clipboardEnabled, onCheckedChange = onClipboard)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("仅识别 B 站、抖音和 YouTube 视频，同一视频不重复提醒。普通文本与敏感内容不会保存。", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Surface(shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("在其他应用中添加下载", style = MaterialTheme.typography.titleMedium)
                Text("系统不允许后台读取剪贴板。在视频应用中选择「分享 → 更多 → 逗逼下载器」，确认后即可下载。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if(android.os.Build.VERSION.SDK_INT >= 36) Surface(onClick = { ClipboardLivePrompt.settings(context) }, shape = RoundedCornerShape(22.dp)) {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("流体云与实时活动", style = MaterialTheme.typography.titleMedium)
                    Text("在系统通知设置中允许显示实时活动。支持时优先使用流体云；应用前台保留避开顶部的提示。",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Glyph("arrow")
            }
        }
        Surface(onClick = {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }, shape = RoundedCornerShape(22.dp)) {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("下载通知", style = MaterialTheme.typography.titleMedium)
                    Text("管理进度与完成提醒", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Glyph("arrow")
            }
        }
        Surface(shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("保存位置", style = MaterialTheme.typography.titleMedium)
                Text("视频：Movies / 逗逼下载器\n图片：Pictures / 逗逼下载器", style = MaterialTheme.typography.bodyLarge)
                Text("下载内容自动保存到系统相册。B 站和 YouTube 音视频自动合并；抖音图集可保存图片或合成为视频。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        com.daxiaamu.dbdown.update.AboutUpdateCard()
    }
}
