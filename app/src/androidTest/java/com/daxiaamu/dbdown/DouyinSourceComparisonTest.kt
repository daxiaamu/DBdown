package com.daxiaamu.dbdown

import android.media.MediaMetadataRetriever
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class DouyinSourceComparisonTest {
    @Test fun compareReportedWork()=runBlocking<Unit> {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("douyinCompare")=="true")
        val context=instrumentation.targetContext
        val id="7690882615418703138"
        val link=Links.detect("https://www.douyin.com/video/$id")!!
        val directory=File(context.getExternalFilesDir(null),"quality-$id").apply { mkdirs() }
        fun report(text:String)=instrumentation.sendStatus(0,android.os.Bundle().apply { putString("stream",text+"\n") })
        fun inspect(file:File,label:String) {
            val result=com.arthenica.ffmpegkit.FFprobeKit.executeWithArguments(arrayOf("-v","error","-show_streams","-show_format","-of","json",file.absolutePath))
            check(com.arthenica.ffmpegkit.ReturnCode.isSuccess(result.returnCode))
            val json=JSONObject(result.output); val tracks=json.getJSONArray("streams")
            val video=(0 until tracks.length()).map { tracks.getJSONObject(it) }.first { it.optString("codec_type")=="video" }
            report("$label bytes=${file.length()} video=${video.optInt("width")}x${video.optInt("height")} codec=${video.optString("codec_name")} bitrate=${video.optString("bit_rate")} fps=${video.optString("avg_frame_rate")} duration=${json.getJSONObject("format").optString("duration")}")
            MediaMetadataRetriever().use { reader ->
                reader.setDataSource(file.absolutePath)
                for(second in listOf(3,10)) reader.getFrameAtTime(second*1000000L,MediaMetadataRetriever.OPTION_CLOSEST)?.let { bitmap ->
                    File(directory,"$label-$second.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
                }
            }
        }
        if(InstrumentationRegistry.getArguments().getString("douyinOffline") == "true") {
            inspect(File(directory, "mobile.mp4"), "web720")
            inspect(File(directory, "desktop.mp4"), "desktop1080")
            return@runBlocking
        }
        val existing=(context.applicationContext as DownloaderApp).store.tasks.value.firstOrNull { it.key=="dy:$id" && it.status==TaskStatus.COMPLETED }
        if(existing!=null) {
            val file=File(directory,"existing.mp4")
            context.contentResolver.openInputStream(android.net.Uri.parse(existing.uri))!!.use { input -> file.outputStream().use { input.copyTo(it) } }
            inspect(file,"existing")
        }
        val info=VideoResolver().resolve(link)
        val client=VideoResolver.client.newBuilder().callTimeout(0,TimeUnit.SECONDS).build()
        val current=File(directory,"current.mp4")
        downloadWithFallback(listOf(info.video)+info.videoFallbacks) { url -> ResumableTransfer(client) {}.download(url,current,info.id,info.userAgent,info.referer) { _,_,_ -> } }
        inspect(current,"current")
        val desktop=DouyinDesktop.detail(id)
        val item=JSONObject(desktop); val rates=item.getJSONObject("video").optJSONArray("bitRateList")
        for(i in 0 until (rates?.length() ?: 0)) { val rate=rates!!.getJSONObject(i); report("DESKTOP ${rate.optString("gearName")} ${rate.optInt("width")}x${rate.optInt("height")} bitrate=${rate.optLong("bitRate")} h265=${rate.optInt("isH265")}") }
        val high=File(directory,"desktop.mp4")
        downloadWithFallback(DouyinPage.desktopVideoUrls(desktop,link)) { url -> ResumableTransfer(client) {}.download(url,high,info.id+"desktop",VideoResolver.DESKTOP,info.referer) { _,_,_ -> } }
        inspect(high,"desktop")
        report("FILES ${directory.absolutePath}")
    }
}
