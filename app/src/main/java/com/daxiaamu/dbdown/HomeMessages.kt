package com.daxiaamu.dbdown

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.absoluteValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
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
        val count = config.messages.size
        val initialPage = if(count > 1) Int.MAX_VALUE / 2 / count * count else 0
        val pager = rememberPagerState(initialPage = initialPage) { if(count > 1) Int.MAX_VALUE else 1 }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val context = LocalContext.current
        val dragging by pager.interactionSource.collectIsDraggedAsState()
        // Automatic scrolling must not cancel its own animation when isScrollInProgress changes.
        LaunchedEffect(config, active, dragging, lifecycle) {
            if(active && config.messages.size > 1 && !dragging) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    while(true) {
                        delay(config.intervalSeconds * 1000L)
                        if(!pager.isScrollInProgress) {
                            pager.animateScrollToPage(pager.settledPage + 1,
                                animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing))
                        }
                    }
                }
            }
        }
        VerticalPager(pager, Modifier.fillMaxWidth().height(48.dp).testTag("homeMessages")) { index ->
            val message = config.messages[index % count]
            Box(Modifier.fillMaxSize().graphicsLayer {
                val offset = (pager.currentPage - index) + pager.currentPageOffsetFraction
                // Keep motion subtle: crossfade with a short vertical travel, including the last-to-first transition.
                translationY = offset * size.height * 0.5f
                alpha = (1f - offset.absoluteValue).coerceIn(0f, 1f)
            }.then(if(message.url != null) Modifier.clickable(onClickLabel = "打开链接") {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(message.url))) }
                    .onFailure { Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show() }
            } else Modifier).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(message.text, modifier = Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                        textDecoration = if(message.url != null) TextDecoration.Underline else TextDecoration.None,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if(message.url != null) Glyph("link", "打开链接", Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
