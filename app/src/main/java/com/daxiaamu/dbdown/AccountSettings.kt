package com.daxiaamu.dbdown

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable fun AccountSettings() {
    val context = LocalContext.current
    val statuses by WebAccounts.statuses.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { WebAccounts.refresh(true) }
    var confirmClear by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    Text("平台账号", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Surface(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Platform.accountPlatforms.forEach { platform ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if(platform == Platform.BILI) "哔哩哔哩" else platform.label, style = MaterialTheme.typography.titleMedium)
                        Text((statuses[platform] ?: AccountStatus.CHECKING).label,
                            style = MaterialTheme.typography.bodySmall, color = if(statuses[platform] == AccountStatus.EXPIRED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(enabled = !clearing, onClick = {
                        context.startActivity(Intent(context, LoginActivity::class.java).putExtra("platform", platform.name))
                    }) { Text(when(statuses[platform]) { AccountStatus.EXPIRED -> "重新登录"; AccountStatus.VALID -> "管理登录"; else -> "网页登录" }) }
                }
                if(platform != Platform.accountPlatforms.last()) HorizontalDivider()
            }
            Text("登录后使用账号可访问的画质与资源。会员、地区和平台验证限制仍以网站为准。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("登录信息只保存在本机，用于对应平台的请求。不会读取或保存密码，登录过期后请重新登录。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(enabled = !clearing, onClick = { confirmClear = true }) {
                Text(if(clearing) "正在清除…" else "清除全部网页登录")
            }
        }
    }
    if(confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text("清除网页登录？") },
        text = { Text("退出本应用内的 B 站、抖音、YouTube 和微博 网页登录，清除本地网页 Cookie 和存储。手机上的官方应用不受影响。正在进行的下载不会重新解析资源。") },
        confirmButton = {
            TextButton(onClick = {
                confirmClear = false; clearing = true
                WebAccounts.clearAll { clearing = false }
            }) { Text("清除") }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
}

@Composable fun AccountExpiryPrompt(onSettings: () -> Unit) {
    val expired by WebAccounts.expiredPrompt.collectAsStateWithLifecycle()
    if(expired.isNotEmpty()) AlertDialog(
        onDismissRequest = WebAccounts::dismissExpiry,
        title = { Text("登录已失效") },
        text = { Text(expired.joinToString("、") { it.label } + "登录已失效，请前往设置重新登录。") },
        confirmButton = { TextButton(onClick = { WebAccounts.dismissExpiry(); onSettings() }) { Text("前往设置") } },
        dismissButton = { TextButton(onClick = WebAccounts::dismissExpiry) { Text("忽略") } })
}
