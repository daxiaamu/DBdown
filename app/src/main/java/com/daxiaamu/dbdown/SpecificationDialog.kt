package com.daxiaamu.dbdown

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable internal fun SpecificationDialog(editor: SpecificationEditor) {
    val state=editor.state ?: return
    val catalog=state.catalog
    val video=catalog?.videos?.find { it.id == state.selection?.video }
    val valid=video != null
    AlertDialog(onDismissRequest=editor::dismiss,shape=RoundedCornerShape(28.dp),
        title={ Text("下载规格") }, text={
            Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
                if(state.loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("正在获取可用的视频和音频规格…")
                }
                if(catalog != null) {
                    TrackPicker("视频",catalog.videos,state.selection?.video,!state.applying,editor::video)
                    val embedded=video?.hasAudio == true
                    val audioOptions=listOf(TrackOption("",if(embedded) "视频内置音轨" else "无")) +
                        if(embedded) emptyList() else catalog.audios
                    TrackPicker("音轨",audioOptions,state.selection?.audio.orEmpty(),
                        !state.applying && video != null && !embedded,editor::audio,emptyLabel=if(embedded) null else "请选择音轨")
                    Text(when {
                        embedded -> "视频自带音轨，将保留原音轨。"
                        state.selection?.audio == null -> "未选择音轨，将下载无声视频。"
                        else -> "将合并所选视频和音轨，保存为一个文件。"
                    },
                        style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("确定后将停止当前任务，按所选规格重新下载。",style=MaterialTheme.typography.bodySmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                if(state.applying) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("正在验证所选规格…",style=MaterialTheme.typography.bodySmall)
                }
            }
        },confirmButton={
            if(catalog == null && !state.loading) TextButton(onClick={ editor.open(state.taskId) }) { Text("重试") }
            else Button(onClick={ editor.confirm() },enabled=valid && !state.loading && !state.applying,
                modifier=Modifier.testTag("confirmSpecification")) { Text("确定") }
        },dismissButton={ TextButton(onClick=editor::dismiss,enabled=!state.applying) { Text("取消") } })
    if(state.confirmFiles) DestructiveFileConfirmation("删除原文件并重新下载？",
        "该任务已保存文件。继续会删除原文件，并按所选视频和音频规格重新下载。此操作无法撤销。",
        "删除并重新下载",false,editor::cancelConfirmation,{ editor.confirm(true) },"confirmReplaceFiles")
}

@Composable private fun TrackPicker(label: String, options: List<TrackOption>, selected: String?, enabled: Boolean, choose: (String)->Unit, emptyLabel: String? = null) {
    var expanded by remember { mutableStateOf(false) }
    val option=options.find { it.id == selected }
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            Surface(onClick={ expanded=true },enabled=enabled && options.isNotEmpty(),shape=RoundedCornerShape(14.dp),
                border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),modifier=Modifier.fillMaxWidth().testTag("spec-$label")) {
                Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if(selected.isNullOrEmpty() && emptyLabel != null) emptyLabel else option?.label ?: "请选择$label",style=MaterialTheme.typography.bodyMedium)
                        option?.detail?.takeIf(String::isNotBlank)?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if(enabled) Glyph("expand",modifier=Modifier.size(18.dp))
                }
            }
            DropdownMenu(expanded=expanded,onDismissRequest={ expanded=false },modifier=Modifier.heightIn(max=340.dp).widthIn(max=360.dp)) {
                options.forEach { item ->
                    DropdownMenuItem(text={ Column {
                        Text(item.label,maxLines=2,overflow=TextOverflow.Ellipsis)
                        if(item.detail.isNotBlank()) Text(item.detail,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    } },leadingIcon={ RadioButton(selected=item.id==selected,onClick=null) },onClick={ choose(item.id); expanded=false })
                }
            }
        }
    }
}

@Composable internal fun DestructiveFileConfirmation(title: String, message: String, confirmLabel: String,
    busy: Boolean, dismiss: ()->Unit, confirm: ()->Unit, tag: String) {
    AlertDialog(onDismissRequest=dismiss,shape=RoundedCornerShape(28.dp),title={ Text(title) },text={ Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(message)
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    } },confirmButton={ Button(onClick=confirm,enabled=!busy,colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error),
        modifier=Modifier.testTag(tag)) { Text(confirmLabel) } },dismissButton={ TextButton(onClick=dismiss,enabled=!busy) { Text("取消") } })
}
