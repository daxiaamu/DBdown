package com.daxiaamu.dbdown

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.json.JSONObject
import org.junit.Test
import org.junit.Assume.assumeTrue

class LivePhotoProbeTest {
    @Test fun inspectSharedVideoFailure() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("sharedVideoProbe") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val store = (instrumentation.targetContext.applicationContext as DownloaderApp).store
        store.tasks.value.filter { it.key.contains("7690868181367262506") || it.source.contains("XXUlB9hIlIM") }.forEach { task ->
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "Task status=${task.status}, error=${task.error}\n") })
        }
        val info = VideoResolver().resolve(Links.detect("https://v.douyin.com/XXUlB9hIlIM/")!!)
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "Resolved images=${info.images.size}, video=${info.video.isNotEmpty()}\n") })
        val file = java.io.File.createTempFile("shared-video-", ".mp4", instrumentation.targetContext.cacheDir)
        try {
            downloadWithFallback(listOf(info.video) + info.videoFallbacks,
                refreshOnUnavailable = { DouyinPage.desktopVideoUrls(DouyinDesktop.detail(info.id.removePrefix("dy:")), info.source) }) { url ->
                instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "Trying candidate host=${java.net.URI(url).host}\n") })
                ResumableTransfer(VideoResolver.client) {}.download(url, file, info.id, info.userAgent, info.referer) { _, _, _ -> }
            }
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "Saved bytes=${file.length()} durationMs=${retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)} resolution=${partialVideoResolution(file)}\n") })
            } finally { retriever.release() }
        } finally { file.delete(); java.io.File(file.path + ".resume").delete() }

    }
    @Test fun saveLivePhotos() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("livePhotoSave") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val store = (context.applicationContext as DownloaderApp).store
            check(store.tasks.value.none { it.status.pending }) { "Do not interrupt existing downloads" }
            val paused = store.paused.value
            try {
                val link = Links.detect("https://www.douyin.com/slides/7690852573347944427")!!
                var id = ""
                scenario.onActivity {
                    store.resumeAll()
                    val existing = store.tasks.value.firstOrNull { it.key == link.key && it.status == TaskStatus.COMPLETED }
                    id = existing?.id ?: store.add(link)!!.id
                    if(existing != null && InstrumentationRegistry.getArguments().getString("livePhotoRedownload") == "true") {
                        store.update(existing.id) { it.copy(status = TaskStatus.QUEUED, uri = "", outputUris = emptyList()) }
                    }
                    if(store.get(id)!!.status == TaskStatus.QUEUED) DownloadService.start(context)
                }
                val deadline = System.currentTimeMillis() + 120_000
                while(store.get(id)!!.status.active && System.currentTimeMillis() < deadline) Thread.sleep(300)
                val task = store.get(id)!!
                org.junit.Assert.assertEquals(task.error, TaskStatus.COMPLETED, task.status)
                val images = task.outputUris.filter { context.contentResolver.getType(android.net.Uri.parse(it)) == "image/jpeg" }
                org.junit.Assert.assertEquals(2, images.size)
                for(uri in images) {
                    val bytes = context.contentResolver.openInputStream(android.net.Uri.parse(uri))!!.use { it.readBytes() }
                    val text = bytes.toString(Charsets.ISO_8859_1)
                    org.junit.Assert.assertTrue(text.contains("MotionPhotoOwner"))
                    org.junit.Assert.assertFalse(text.contains("GCamera:MicroVideo"))
                    org.junit.Assert.assertTrue(text.contains("ftyp"))
                }
                java.io.File(context.getExternalFilesDir(null), "live-saved-uris.txt").writeText(images.joinToString("\n"))
                instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "Saved 2 Motion Photos with embedded MP4\n") })
            } finally { if(paused) store.pauseAll() }
        }
    }
    @Test fun resolveMissingClips() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("livePhotoResolve") == "true")
        val info = VideoResolver().resolve(Links.detect("https://v.douyin.com/Bt6e6Qmw2n4/")!!)
        org.junit.Assert.assertEquals(2, info.images.size)
        org.junit.Assert.assertEquals(2, info.imageVideos.count { it != null })
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putString("stream", "Resolved 2 images with 2 matching Live Photo clips\n")
        })
    }
    @Test fun inspectLiveResources() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("livePhotoProbe") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        WebAccounts.refresh(true)
        for(id in listOf("7690852573347944427", "7688970467424551275")) {
            val url = "https://www.iesdouyin.com/web/api/v2/aweme/slidesinfo/?aweme_ids=%5B$id%5D&request_source=200"
            val raw = VideoResolver.client.newCall(Request.Builder().url(url).header("User-Agent", VideoResolver.MOBILE)
                .header("Referer", "https://www.iesdouyin.com/share/slides/$id/").build()).execute().use { it.body!!.string() }
            val item = JSONObject(raw).getJSONArray("aweme_details").getJSONObject(0)
            val desktop = VideoResolver.client.newCall(Request.Builder()
                .url("https://www.douyin.com/jingxuan?modal_id=$id")
                .header("User-Agent", VideoResolver.DESKTOP).header("Referer", "https://www.douyin.com/").build())
                .execute().use { it.body!!.string() }
            java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "live-desktop-$id.html").writeText(desktop)
            val images = item.getJSONArray("images")
            val types = (0 until images.length()).map { images.getJSONObject(it).optInt("clip_type") }
            val info = DouyinPage.parseSlides(raw, Links.detect("https://www.douyin.com/slides/$id")!!)
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putString("stream", "id=$id desktopBytes=${desktop.length} render=${desktop.contains("RENDER_DATA")} images=${info.images.size} clips=$types liveVideos=${info.imageVideos.count { it != null }}\n")
            })
        }
    }
}
