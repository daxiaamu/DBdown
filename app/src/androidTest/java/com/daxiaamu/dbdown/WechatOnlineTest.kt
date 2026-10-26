package com.daxiaamu.dbdown

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.json.JSONObject
import org.junit.Test
import org.junit.Assume.assumeTrue

class WechatOnlineTest {
    @Test fun inspectSessionAndResolveSharedVideo() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("wechatOnline") == "true")
        val i = InstrumentationRegistry.getInstrumentation()
        fun report(text: String) = i.sendStatus(0, android.os.Bundle().apply { putString("stream", text + "\n") })
        val request = Request.Builder().url("https://yuanbao.tencent.com/api/getuserinfo")
            .header("User-Agent", VideoResolver.DESKTOP).header("Referer", "https://yuanbao.tencent.com/")
            .header("x-source", "web").build()
        VideoResolver.client.newCall(request).execute().use { r ->
            val json = runCatching { JSONObject(r.body!!.string()) }.getOrDefault(JSONObject())
            org.junit.Assert.assertEquals(AccountStatus.VALID, accountVerdict(Platform.WECHAT, json.toString()))
            report("Account verified: HTTP=${r.code}, status=${json.optInt("status",-1)}")
        }
        val info = VideoResolver().resolve(Links.detect("https://weixin.qq.com/sph/AsTZuV2FYU")!!)
        report("Resolved title=${info.title}, encrypted=${info.videoDecodeKey != null}, formats=${info.specifications?.videos?.size}")
        val app = i.targetContext.applicationContext as DownloaderApp
        check(app.store.tasks.value.none { it.status.pending }) { "Do not interrupt existing downloads" }
        i.runOnMainSync { i.targetContext.startActivity(android.content.Intent(i.targetContext, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        Thread.sleep(1000)
        var id = ""
        i.runOnMainSync {
            id = app.store.add(info.source).id
            app.prepareDownload(id, info)
            DownloadService.start(i.targetContext)
        }
        val deadline = System.currentTimeMillis() + 180_000
        while(app.store.get(id)!!.status.active && System.currentTimeMillis() < deadline) Thread.sleep(500)
        val task = app.store.get(id)!!
        org.junit.Assert.assertEquals(task.error, TaskStatus.COMPLETED, task.status)
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(i.targetContext, android.net.Uri.parse(task.uri), null)
            org.junit.Assert.assertTrue((0 until extractor.trackCount).any {
                extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true
            })
            org.junit.Assert.assertTrue((0 until extractor.trackCount).any {
                extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            })
        } finally { extractor.release() }
        report("Saved playable video: bytes=${task.bytes}, resolution=${task.resolution}, fps=${task.fps}")
    }
}
