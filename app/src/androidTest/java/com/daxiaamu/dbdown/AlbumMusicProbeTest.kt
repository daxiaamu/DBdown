package com.daxiaamu.dbdown

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.json.JSONObject
import org.junit.Test
import org.junit.Assume.assumeTrue

/** Opt-in diagnostic: report media fields only; never cookies or account data. */
class AlbumMusicProbeTest {
    @Test fun inspectSharedAlbumMusic() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("albumMusicProbe") == "true")
        WebAccounts.refresh(true)
        val id = InstrumentationRegistry.getArguments().getString("albumId") ?: "7690817789985360570"
        val url = "https://www.douyin.com/share/note/$id/"
        val page = VideoResolver.client.newCall(Request.Builder().url(url).header("User-Agent", VideoResolver.MOBILE)
            .header("Referer", "https://www.douyin.com/").build()).execute().use { it.body!!.string() }
        val raw = Regex("""window\._ROUTER_DATA\s*=\s*(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(page)!!.groupValues[1].trim().trimEnd(';')
        val items = JSONObject(raw).getJSONObject("loaderData").getJSONObject("note_(id)/page")
            .getJSONObject("videoInfoRes").getJSONArray("item_list")
        val item = (0 until items.length()).map { items.getJSONObject(it) }.first { it.getString("aweme_id") == id }
        val music = item.optJSONObject("music")
        val summary = JSONObject().put("id", id).put("images", item.optJSONArray("images")?.length())
            .put("musicKeys", music?.keys()?.asSequence()?.toList()?.joinToString())
            .put("musicPlay", music?.optJSONObject("play_url"))
            .put("videoPlay", item.optJSONObject("video")?.optJSONObject("play_addr"))
            .put("musicStatus", music?.opt("status")).put("musicId", music?.opt("id"))
        val info = VideoResolver().resolve(Links.detect(url)!!)
        summary.put("selectedMusic", info.music ?: JSONObject.NULL)
        org.junit.Assert.assertNotNull("Expected soundtrack for this sample", info.music)
        val file = java.io.File.createTempFile("album-music-", ".m4a", instrumentation.targetContext.cacheDir)
        try {
            ResumableTransfer(VideoResolver.client) {}.download(info.music!!, file, info.id, info.userAgent, info.referer) { _, _, _ -> }
            val extractor = android.media.MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                org.junit.Assert.assertTrue(extractor.trackCount > 0)
                for(index in 0 until extractor.trackCount) org.junit.Assert.assertTrue(
                    extractor.getTrackFormat(index).getString(android.media.MediaFormat.KEY_MIME)!!.startsWith("audio/"))
                summary.put("durationUs", extractor.getTrackFormat(0).getLong(android.media.MediaFormat.KEY_DURATION))
            } finally { extractor.release() }
        } finally { file.delete() }
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "album-music-probe.json").writeText(summary.toString())
        instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("stream", "Album images=${info.images.size}, selectedMusic=${info.music != null}, musicFields=${music?.length()}\n")
        })
    }
    @Test fun saveSharedAlbumWithMusic() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("albumMusicProbe") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val store = (context.applicationContext as DownloaderApp).store
            check(store.tasks.value.none { it.status.active }) { "Do not interrupt existing downloads" }
            val paused = store.paused.value
            try {
                val workId = InstrumentationRegistry.getArguments().getString("albumId") ?: "7690817789985360570"
                val link = Links.detect("https://www.douyin.com/note/$workId")!!
                var id = ""
                scenario.onActivity {
                    store.resumeAll()
                    val existing = store.tasks.value.firstOrNull { it.key == link.key }
                    id = existing?.id ?: store.add(link).id
                    if(existing == null || existing.status != TaskStatus.COMPLETED) DownloadService.start(context)
                }
                val deadline = System.currentTimeMillis() + 120_000
                while(store.get(id)!!.status.active && System.currentTimeMillis() < deadline) Thread.sleep(300)
                val task = store.get(id)!!
                org.junit.Assert.assertEquals(task.error, TaskStatus.COMPLETED, task.status)
                val types = task.outputUris.associateWith { context.contentResolver.getType(android.net.Uri.parse(it)).orEmpty() }
                org.junit.Assert.assertTrue(types.values.any { it.startsWith("image/") })
                val audio = types.entries.first { it.value.startsWith("audio/") }.key
                val extractor = android.media.MediaExtractor()
                try {
                    extractor.setDataSource(context, android.net.Uri.parse(audio), null)
                    val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                    org.junit.Assert.assertTrue(formats.isNotEmpty())
                    org.junit.Assert.assertTrue(formats.all { it.getString(android.media.MediaFormat.KEY_MIME)!!.startsWith("audio/") })
                    val duration = formats.first().getLong(android.media.MediaFormat.KEY_DURATION)
                    org.junit.Assert.assertTrue(duration > 0)
                    instrumentation.sendStatus(0, android.os.Bundle().apply {
                        putString("stream", "Saved ${types.values.count { it.startsWith("image/") }} image(s), separate audio durationUs=$duration; no video track\n")
                    })
                } finally { extractor.release() }
            } finally { if(paused) store.pauseAll() }
        }
    }

}
