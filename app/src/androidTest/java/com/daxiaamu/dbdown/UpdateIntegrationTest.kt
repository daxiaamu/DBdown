package com.daxiaamu.dbdown

import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.daxiaamu.dbdown.update.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class UpdateIntegrationTest {
    @Composable private fun UpdateScene(manager: UpdateManager) {
        val haze = remember { HazeState() }
        Column(Modifier.fillMaxSize().hazeSource(haze).padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            repeat(18) { Text("下载记录 · 用于检查实时模糊的背景文字") }
        }
        UpdateOverlay(manager, haze)
    }
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(forced: Boolean = false): AcceptedUpdate {
        val current = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        val manifest = JSONObject().put("schemaVersion", 1).put("channel", "stable")
            .put("policyRevision", 1).put("versionCode", current + 1).put("versionName", "更新测试版本")
            .put("maxForcedVersionCode", if(forced) current else 0)
            .put("publishedAt", "2026-08-12T08:00:00Z").put("changelog",
                "## 改进\n**重要说明** [官网](https://github.com/daxiaamu/DBdown)\n" + "- 长日志测试\n".repeat(120))
            .put("sha256", "a".repeat(64)).put("size", 10)
            .put("urls", JSONArray((1..5).map { "https://cdn$it.example/update.apk" })).toString()
        val hash = UpdateProtocol.sha256(manifest.toByteArray())
        val pointer = UpdateProtocol.pointer(JSONObject().put("schemaVersion", 1).put("channel", "stable")
            .put("policyRevision", 1).put("manifestSha256", hash)
            .put("manifestPath", "updates/stable/manifests/1-$hash.json").put("expiresAt", "2099-01-01T00:00:00Z").toString(), "stable")
        return AcceptedUpdate(pointer, UpdateProtocol.manifest(manifest, pointer))
    }

    @Test fun aboutShowsActualVersionAndCheckTextButton() {
        rule.runOnIdle { ViewModelProvider(rule.activity)[MainViewModel::class.java].settings = true }
        rule.onNodeWithTag("aboutUpdate").performScrollTo()
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        rule.onNodeWithText("版本 $version").assertIsDisplayed()
        rule.waitUntil(30000) { !(context.applicationContext as DownloaderApp).updates.state.value.checking }
        rule.waitForIdle()
        rule.onNodeWithTag("checkUpdate").assertHasClickAction()
    }

    @Test fun skipManualIgnoreAndLongDialogHaveVisibleActions() {
        val name = "update-test-" + UUID.randomUUID()
        var manager: UpdateManager? = null
        try {
            rule.runOnUiThread {
                manager = UpdateManager(context, name) { _, _ -> fixture() }
                rule.activity.setContent { DownloaderTheme { UpdateScene(manager!!) } }
                manager!!.check(false)
            }
            rule.waitUntil(5000) { manager!!.state.value.dialog }
            rule.onNodeWithText("下载安装").assertIsDisplayed()
            rule.waitForIdle()
            android.os.SystemClock.sleep(700) // Allow the platform dialog window enter animation to finish.
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(context.getExternalFilesDir(null), "update-glass.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
            rule.onNodeWithText("跳过此版本").assertIsDisplayed().performClick()
            rule.runOnIdle { assertFalse(manager!!.state.value.dialog); manager!!.close() }
            rule.runOnUiThread {
                manager = UpdateManager(context, name) { _, _ -> fixture() }
                rule.activity.setContent { DownloaderTheme { UpdateScene(manager!!) } }
                manager!!.check(false)
            }
            rule.waitUntil(5000) { !manager!!.state.value.checking }
            assertFalse(manager!!.state.value.dialog)
            rule.runOnIdle { manager!!.check(true) }
            rule.waitUntil(5000) { manager!!.state.value.dialog }
            rule.onNodeWithText("忽略").assertIsDisplayed().performClick()
            rule.runOnIdle { assertTrue(manager!!.state.value.redDot); assertFalse(manager!!.state.value.dialog) }
        } finally {
            rule.runOnUiThread { manager?.close() }
            context.deleteSharedPreferences(name)
        }
    }

    @Test fun forcedPolicySurvivesOfflineAndOverridesSkip() {
        val name = "update-test-" + UUID.randomUUID()
        var manager: UpdateManager? = null
        try {
            val update = fixture(true)
            context.getSharedPreferences(name, 0).edit().putLong("skip-stable", update.manifest.versionCode).apply()
            rule.runOnUiThread {
                manager = UpdateManager(context, name) { _, _ -> update }
                rule.activity.setContent { DownloaderTheme { UpdateScene(manager!!) } }
                manager!!.check(false)
            }
            rule.waitUntil(5000) { manager!!.state.value.dialog }
            rule.onNodeWithText("跳过此版本").assertDoesNotExist()
            rule.onNodeWithText("忽略").assertDoesNotExist()
            rule.runOnIdle { manager!!.dismiss(false); assertTrue(manager!!.state.value.dialog); manager!!.close() }
            rule.runOnUiThread {
                manager = UpdateManager(context, name) { _, _ -> error("offline") }
                rule.activity.setContent { DownloaderTheme { UpdateScene(manager!!) } }
                manager!!.check(true)
            }
            rule.waitUntil(5000) { !manager!!.state.value.checking }
            rule.runOnIdle { assertTrue(manager!!.state.value.dialog); assertNotNull(manager!!.state.value.error) }
            rule.onNodeWithText("需要更新").assertIsDisplayed()
        } finally {
            rule.runOnUiThread { manager?.close() }
            context.deleteSharedPreferences(name)
        }
    }

    @Test fun badCdnHashFallsBackToNextHttpsSourceBeforeAcceptingApk() = runBlocking {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val bytes = File(context.applicationInfo.sourceDir).readBytes()
        val bad = bytes.copyOf().apply { this[100] = (this[100].toInt() xor 1).toByte() }
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.protocols = listOf(okhttp3.Protocol.HTTP_1_1)
            server.useHttps(TestTls.socketFactory, false)
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(okio.Buffer().write(bad)))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setChunkedBody(okio.Buffer().write(bytes), 128 * 1024))
            val client = UpdateSource.client.newBuilder().protocols(listOf(okhttp3.Protocol.HTTP_1_1)).sslSocketFactory(TestTls.socketFactory, TestTls.trustManager).build()
            val apk = UpdateApk(context, client)
            val model = fixture().manifest.copy(versionCode = info.longVersionCode, size = bytes.size.toLong(),
                sha256 = UpdateProtocol.sha256(bytes), urls = listOf(server.url("/bad").toString(), server.url("/good").toString()))
            val progress = mutableListOf<Float?>()
            try {
                val result = apk.download(model) { progress.add(it) }
                assertEquals(2, server.requestCount)
                assertEquals(model.sha256, UpdateProtocol.sha256(result.readBytes()))
                assertTrue(progress.contains(null))
                assertEquals(1f, progress.last())
                apk.verify(model, result)
            } finally { apk.file(model).delete() }
        }
    }

    @Test fun apkIsRehashedAndIdentityCheckedOnEveryVerification() = runBlocking {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val file = File(context.cacheDir, "update-verify-test.apk")
        try {
            File(context.applicationInfo.sourceDir).copyTo(file, overwrite = true)
            val model = fixture().manifest.copy(versionCode = info.longVersionCode, size = file.length(),
                sha256 = UpdateProtocol.sha256(file.readBytes()))
            val verifier = UpdateApk(context)
            verifier.verify(model, file)
            assertTrue(runCatching { verifier.verify(model.copy(versionCode = info.longVersionCode + 1), file) }.isFailure)
            java.io.RandomAccessFile(file, "rw").use { it.seek(100); val byte = it.readByte(); it.seek(100); it.writeByte(byte.toInt() xor 1) }
            assertTrue(runCatching { verifier.verify(model, file) }.isFailure)
        } finally { file.delete() }
    }
}
