package com.daxiaamu.dbdown

import androidx.compose.runtime.Composable

@Composable internal fun ClipboardSuggestionNotifications(vm: MainViewModel,
    requestNotifications: () -> Unit = {}, openInput: (String) -> Unit = vm::openInput) {
    val prompt=vm.clipboardPrompt?.takeUnless { vm.inputVisible || !vm.clipboardEnabled }
    val ready=(prompt as? ClipboardPrompt.Ready)?.info
    val resolving=prompt is ClipboardPrompt.Resolving
    val failed=prompt is ClipboardPrompt.Failed
    val title=when {
        ready != null -> "发现${ready.source.platform.label}${if(ready.images.isEmpty()) "视频" else "图集 · ${ready.images.size} 张"}"
        failed -> "暂时无法解析链接"
        else -> "发现${prompt?.link?.platform?.label.orEmpty()}链接"
    }
    val text=when {
        ready != null -> ready.title
        failed -> "解析失败，请检查网络后重试。"
        else -> "发现可下载内容，正在解析…"
    }
    val actions=when {
        ready != null -> listOf(ClipboardPromptAction("下载") { if(vm.downloadSuggestion()) requestNotifications() })
        failed -> listOf(ClipboardPromptAction("重试",vm::retryClipboard))
        else -> emptyList()
    }
    ClipboardPromptEffect(prompt?.key,title,text,
        if(resolving) "正在解析" else if(failed) "解析失败" else "下载作品",actions,
        open={ prompt?.let { openInput(it.link.url) } },dismiss=vm::dismissClipboard,
        expire={ if(vm.clipboardPrompt !is ClipboardPrompt.Resolving) vm.dismissClipboard() })
}
