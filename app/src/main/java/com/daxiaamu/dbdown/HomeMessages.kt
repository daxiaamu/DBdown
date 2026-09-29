package com.daxiaamu.dbdown

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.URI

internal data class HomeMessage(val id: String, val text: String, val url: String?)
internal data class HomeMessages(val enabled: Boolean, val intervalSeconds: Int, val messages: List<HomeMessage>) {
    companion object {
        val Empty = HomeMessages(false, 5, emptyList())
        fun parse(raw: String): HomeMessages {
            val json = JSONObject(raw)
            require(json.getInt("schemaVersion") == 1)
            if(!json.getBoolean("enabled")) return Empty
            val interval = json.getInt("intervalSeconds")
            require(interval in 2..120)
            val items = json.getJSONArray("messages")
            require(items.length() <= 30)
            val messages = (0 until items.length()).map { index ->
                val item = items.getJSONObject(index)
                val id = item.getString("id").trim()
                val text = item.getString("text").trim()
                require(id.isNotEmpty() && id.length <= 80 && text.isNotEmpty() && text.length <= 300)
                val url = item.optString("url").trim().takeIf { it.isNotEmpty() }
                if(url != null) {
                    val uri = URI(url)
                    require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null)
                }
                HomeMessage(id, text, url)
            }
            require(messages.map { it.id }.distinct().size == messages.size)
            return HomeMessages(true, interval, messages)
        }
    }
}

@Composable internal fun HomeMessageCarousel(config: HomeMessages, active: Boolean) {
    if(!config.enabled || config.messages.isEmpty()) return
    key(config) {
        val pager = rememberPagerState { config.messages.size }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val context = LocalContext.current
        LaunchedEffect(config, active, pager.settledPage, pager.isScrollInProgress) {
            if(active && config.messages.size > 1 && !pager.isScrollInProgress) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    delay(config.intervalSeconds * 1000L)
                    pager.animateScrollToPage((pager.settledPage + 1) % config.messages.size)
                }
            }
        }
        HorizontalPager(pager, Modifier.fillMaxWidth().height(48.dp).testTag("homeMessages")) { index ->
            val message = config.messages[index]
            Box(Modifier.fillMaxSize().then(if(message.url != null) Modifier.clickable(onClickLabel = "打开链接") {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(message.url))) }
                    .onFailure { Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show() }
            } else Modifier).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                Text(message.text, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
