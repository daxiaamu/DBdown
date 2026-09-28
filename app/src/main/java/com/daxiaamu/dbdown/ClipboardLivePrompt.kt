package com.daxiaamu.dbdown

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import java.util.UUID

internal data class ClipboardPromptAction(val label: String, val run: () -> Unit)

/** One short-lived prompt. Intents contain an opaque, single-use token, never clipboard text. */
internal object ClipboardLivePrompt {
    const val NOTIFICATION_ID = 9502
    const val ACTION = "com.daxiaamu.dbdown.CLIPBOARD_PROMPT"
    private const val CHANNEL = "clipboard_prompts"
    private const val LIFETIME_MS = 60_000L
    private val handler = Handler(Looper.getMainLooper())
    private data class Entry(
        val token: String, val context: Context, val deadline: Long,
        val actions: List<ClipboardPromptAction>, val open: () -> Unit, val dismiss: () -> Unit,
        val intents: MutableList<PendingIntent> = mutableListOf()
    )
    private var current: Entry? = null
    val isColorOs: Boolean get() = listOf(Build.BRAND, Build.MANUFACTURER).any {
        it.lowercase() in setOf("oppo", "oplus", "oneplus", "realme")
    }
    val avoidSystemIsland: Boolean get() = Build.VERSION.SDK_INT >= 36 || isColorOs
    fun allowed(context: Context): Boolean = Build.VERSION.SDK_INT >= 36 && runCatching {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.areNotificationsEnabled() && manager.canPostPromotedNotifications()
    }.getOrDefault(false)

    fun settings(context: Context) {
        val appSettings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        runCatching { context.startActivity(appSettings) }.onFailure {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }
    }

    fun post(context: Context, title: String, text: String, shortText: String,
             actions: List<ClipboardPromptAction>, open: () -> Unit, dismiss: () -> Unit): String? {
        if(Build.VERSION.SDK_INT < 36 || !allowed(context)) return null
        check(Looper.myLooper() == Looper.getMainLooper())
        current?.let { cancel(it.token) }
        val app = context.applicationContext
        val entry = Entry(UUID.randomUUID().toString(), app, SystemClock.elapsedRealtime() + LIFETIME_MS,
            actions.take(2), open, dismiss)
        current = entry
        return try {
            val manager = app.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "剪贴板链接提示", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "识别链接后，短暂提示填入或下载"; setSound(null, null); enableVibration(false)
            })
            fun pending(index: Int, broadcast: Boolean = false): PendingIntent {
                val intent = Intent(app, if(broadcast) ClipboardPromptDismissReceiver::class.java else MainActivity::class.java)
                    .setAction(ACTION).setData(Uri.parse("dbdown-prompt://${entry.token}/$index"))
                if(!broadcast) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                return (if(broadcast) PendingIntent.getBroadcast(app, 0, intent, PendingIntent.FLAG_IMMUTABLE)
                    else PendingIntent.getActivity(app, 0, intent, PendingIntent.FLAG_IMMUTABLE)).also { entry.intents += it }
            }
            val ignore = pending(-2, broadcast = true)
            val notification = Notification.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_download)
                .setContentTitle(title).setContentText(text.take(160)).setShortCriticalText(shortText)
                .setContentIntent(pending(-1)).setDeleteIntent(ignore)
                .setCategory(Notification.CATEGORY_STATUS).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false).setTimeoutAfter(LIFETIME_MS)
                .setAllowSystemGeneratedContextualActions(false)
                .addExtras(Bundle().apply { putBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING, true) })
            entry.actions.forEachIndexed { index, action ->
                notification.addAction(Notification.Action.Builder(null, action.label, pending(index)).build())
            }
            notification.addAction(Notification.Action.Builder(null, "忽略", ignore).build())
            manager.notify(NOTIFICATION_ID, notification.build())
            handler.postAtTime({
                if(current?.token == entry.token) { cancel(entry.token); entry.dismiss() }
            }, entry.token, SystemClock.uptimeMillis() + LIFETIME_MS)
            entry.token
        } catch(_: Exception) { cancel(entry.token); null }
    }

    fun promoted(token: String): Boolean = current?.takeIf { it.token == token }?.let { entry ->
        Build.VERSION.SDK_INT >= 36 && runCatching {
            entry.context.getSystemService(NotificationManager::class.java).activeNotifications.any {
                it.id == NOTIFICATION_ID && (it.notification.flags and Notification.FLAG_PROMOTED_ONGOING) != 0
            }
        }.getOrDefault(false)
    } ?: false

    fun cancel(token: String) {
        val entry = current?.takeIf { it.token == token } ?: return
        current = null
        handler.removeCallbacksAndMessages(entry.token)
        runCatching { entry.context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }
        entry.intents.forEach { it.cancel() }
    }

    fun handle(intent: Intent?): Boolean {
        if(intent?.action != ACTION) return false
        val uri = intent.data ?: return true
        val entry = current?.takeIf { uri.scheme == "dbdown-prompt" && it.token == uri.host } ?: return true
        val index = uri.lastPathSegment?.toIntOrNull() ?: return true
        val callback = when(index) { -2 -> entry.dismiss; -1 -> entry.open; else -> entry.actions.getOrNull(index)?.run } ?: return true
        if(SystemClock.elapsedRealtime() >= entry.deadline) { cancel(entry.token); entry.dismiss(); return true }
        cancel(entry.token)
        callback()
        return true
    }
}

class ClipboardPromptDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { ClipboardLivePrompt.handle(intent) }
}

/** Promotion does not prove foreground visibility: OEMs can hide the app's own capsule. Keep an inline fallback. */
@Composable internal fun ClipboardPromptEffect(
    key: String?, title: String, text: String, shortText: String, actions: List<ClipboardPromptAction>,
    open: () -> Unit, dismiss: () -> Unit
) {
    val context = LocalContext.current
    val latestActions by rememberUpdatedState(actions)
    val latestOpen by rememberUpdatedState(open)
    val latestDismiss by rememberUpdatedState(dismiss)
    DisposableEffect(key) {
        val token = key?.let { ClipboardLivePrompt.post(context, title, text, shortText,
            actions.mapIndexed { index, action -> ClipboardPromptAction(action.label) { latestActions.getOrNull(index)?.run?.invoke() } },
            { latestOpen() }, { latestDismiss() }) }
        val handler = Handler(Looper.getMainLooper())
        val monitor = object : Runnable {
            override fun run() {
                if(token == null) return
                if(ClipboardLivePrompt.promoted(token)) handler.postDelayed(this, 1000) else ClipboardLivePrompt.cancel(token)
            }
        }
        if(token != null) handler.postDelayed(monitor, 400)
        onDispose { handler.removeCallbacks(monitor); token?.let(ClipboardLivePrompt::cancel) }
    }
}
