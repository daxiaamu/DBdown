package com.daxiaamu.dbdown.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import com.daxiaamu.dbdown.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class UpdateStage { IDLE, DOWNLOADING, VERIFYING, READY, AUTHORIZATION }
data class UpdateState(
    val checking: Boolean = false, val manifest: UpdateManifest? = null,
    val dialog: Boolean = false, val redDot: Boolean = false,
    val stage: UpdateStage = UpdateStage.IDLE, val progress: Float? = null,
    val error: String? = null, val message: String? = null
) {
    val busy get() = stage == UpdateStage.DOWNLOADING || stage == UpdateStage.VERIFYING
}
class UpdateManager internal constructor(private val context: Context,
    preferencesName: String = "app_updates",
    private val fetchUpdate: (suspend (String, AcceptedUpdate?) -> AcceptedUpdate)? = null
) {
    private val prefs = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val versionCode = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    val versionName = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    private val channels = if(Regex("(?i)(alpha|beta|rc)").containsMatchIn(versionName)) listOf("stable", "beta") else listOf("stable")
    private val source = UpdateSource(BuildConfig.UPDATE_REPOSITORY, BuildConfig.UPDATE_BRANCH)
    private val apk = UpdateApk(context)
    private val accepted = channels.mapNotNull { channel -> restore(channel)?.let { channel to it } }.toMap().toMutableMap()
    private val cached = accepted.values.map { it.manifest }.filter { it.versionCode > versionCode }.maxByOrNull { it.versionCode }
    private val _state = MutableStateFlow(UpdateState(manifest = cached,
        dialog = accepted.values.any { it.manifest.required(versionCode) },
        redDot = cached?.let { prefs.getBoolean("ignored-${it.channel}", false) } ?: false))
    val state = _state.asStateFlow()
    private var automaticStarted = false
    private var downloadJob: Job? = null
    private val checks = SharedUpdateCheck(scope, fetch = {
        coroutineScope {
            channels.map { channel -> async {
                try { Result.success(fetchUpdate?.invoke(channel, accepted[channel]) ?: source.fetch(channel, accepted[channel])) }
                catch(e: CancellationException) { throw e }
                catch(e: Exception) { Result.failure<AcceptedUpdate>(e) }
            } }.awaitAll()
        }
    }) { results, manual ->
        results.fold(onSuccess = { replies ->
            val successful = replies.mapNotNull { it.getOrNull() }
            successful.forEach { save(it); accepted[it.manifest.channel] = it }
            val failures = replies.mapNotNull { it.exceptionOrNull() }
            val known = accepted.values.map { it.manifest }.filter { it.versionCode > versionCode }.maxByOrNull { it.versionCode }
            val required = accepted.values.any { it.manifest.required(versionCode) }
            if(known != null && successful.isNotEmpty()) {
                val skipped = prefs.getLong("skip-${known.channel}", -1) == known.versionCode
                _state.update { it.copy(checking = false, manifest = known,
                    dialog = required || manual || !skipped, stage = UpdateStage.IDLE,
                    redDot = prefs.getBoolean("ignored-${known.channel}", false),
                    error = if(failures.isNotEmpty()) "部分更新渠道暂不可用，显示最近确认的版本" else null) }
            } else if(failures.isNotEmpty()) {
                failedCheck(failures.first().message ?: "检查更新失败", manual)
            } else {
                channels.forEach { prefs.edit().remove("ignored-$it").apply() }
                _state.value = UpdateState(message = if(manual) "已是最新版本" else null)
            }
        }, onFailure = { failedCheck(it.message ?: "检查更新失败", manual) })
    }
    fun isRequired(manifest: UpdateManifest) = manifest.required(versionCode) ||
        accepted.values.any { it.manifest.required(versionCode) }
    fun check(manual: Boolean) {
        if(_state.value.busy || _state.value.stage == UpdateStage.AUTHORIZATION) {
            if(manual) _state.update { it.copy(dialog = true) }
            return
        }
        if(!manual) {
            if(automaticStarted) return
            automaticStarted = true
        }
        _state.update { it.copy(checking = true, message = null) }
        checks.request(manual)
    }
    private fun failedCheck(error: String, manual: Boolean) {
        val forced = accepted.values.any { it.manifest.required(versionCode) }
        _state.update { it.copy(checking = false, dialog = it.dialog || forced,
            error = if(forced) error else it.error, message = if(manual && !forced) error else null) }
    }
    fun consumeMessage() { _state.update { it.copy(message = null) } }
    fun dismiss(skip: Boolean) {
        val current = _state.value
        val manifest = current.manifest ?: return
        if(isRequired(manifest) || current.busy || current.stage == UpdateStage.AUTHORIZATION) return
        prefs.edit().putBoolean("ignored-${manifest.channel}", !skip).apply()
        if(skip) prefs.edit().putLong("skip-${manifest.channel}", manifest.versionCode).apply()
        _state.update { it.copy(dialog = false, redDot = !skip) }
    }
    fun install(activity: ComponentActivity) {
        if(downloadJob?.isActive == true || _state.value.checking) return
        val manifest = _state.value.manifest ?: return
        downloadJob = scope.launch {
            try {
                _state.update { it.copy(stage = UpdateStage.VERIFYING, error = null) }
                val file = apk.file(manifest)
                val valid = try { apk.verify(manifest); true } catch(e: CancellationException) { throw e }
                    catch(_: Exception) { false }
                if(!valid) {
                    withContext(Dispatchers.IO) { file.delete() }
                    _state.update { it.copy(stage = UpdateStage.DOWNLOADING, progress = null) }
                    apk.download(manifest) { value -> _state.update { it.copy(progress = value) } }
                }
                // Always hash against the currently displayed JSON immediately before installation.
                check(_state.value.manifest?.identity == manifest.identity) { "更新版本已变化，请重新下载" }
                _state.update { it.copy(stage = UpdateStage.VERIFYING) }
                apk.verify(manifest)
                _state.update { it.copy(stage = UpdateStage.READY, progress = 1f) }
                if(!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
                if(!context.packageManager.canRequestPackageInstalls()) {
                    prefs.edit().putString("awaitingAuthorization", manifest.identity).apply()
                    _state.update { it.copy(stage = UpdateStage.AUTHORIZATION) }
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                    return@launch
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
                activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .apply { clipData = ClipData.newRawUri("DBDown 更新", uri) }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { _state.update { it.copy(stage = UpdateStage.IDLE, error = e.message ?: "更新失败，请重试") } }
        }
    }
    fun onResume(activity: ComponentActivity) {
        val identity = prefs.getString("awaitingAuthorization", null) ?: return
        val manifest = _state.value.manifest ?: return
        if(downloadJob?.isActive == true) return
        prefs.edit().remove("awaitingAuthorization").apply()
        _state.update { it.copy(stage = UpdateStage.READY, dialog = true) }
        if(identity == manifest.identity && context.packageManager.canRequestPackageInstalls()) install(activity)
        else _state.update { it.copy(error = "需要允许安装此来源的应用，点击安装后可重新授权") }
    }
    internal fun close() { scope.cancel() }
    private fun restore(channel: String): AcceptedUpdate? = runCatching {
        val pointer = UpdateProtocol.pointer(prefs.getString("pointer-$channel", null) ?: return null, channel, checkExpiry = false)
        val manifest = UpdateProtocol.manifest(prefs.getString("manifest-$channel", null) ?: return null, pointer)
        AcceptedUpdate(pointer, manifest)
    }.getOrNull()
    private fun save(update: AcceptedUpdate) {
        prefs.edit().putString("pointer-${update.pointer.channel}", update.pointer.raw)
            .putString("manifest-${update.pointer.channel}", update.manifest.raw).apply()
    }
}
