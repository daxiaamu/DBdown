package com.daxiaamu.dbdown

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.Bundle
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class YoutubeMultiClientOnlineTest {
    @Test fun reportsHighestAccessibleFormatsFromBothSamples() = runBlocking(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("youtubeMultiClient")=="true")
        fun report(text: String) { InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply { putString("stream",text+"\n") }) }
        val requested=InstrumentationRegistry.getArguments().getString("youtubeInput")
        val full=InstrumentationRegistry.getArguments().getString("youtubeFull")=="true"
        for(input in requested?.let(::listOf) ?: listOf("b-Ag7meqZoU","t2dhvFTktk4")) {
            val link=requireNotNull(Links.detect(input))
            val id=link.key.removePrefix("yt:")
            val info=YoutubeResolver.resolve(link,onCandidates={ candidates ->
                candidates.groupBy { it.source + "/" + it.protocol }.forEach { (source,tracks) ->
                    val video=tracks.filter { it.video }.maxWithOrNull(youtubeVideoOrder)
                    val audio=tracks.filter { it.audio && !it.video }.maxWithOrNull(youtubeAudioOrder)
                    report("$id $source: ${tracks.size} candidates; video=${video?.width}x${video?.height}/${video?.fps} HDR=${video?.hdr}; audio=${audio?.audioCodec}/${audio?.channels}ch")
                }
            },onClient={ name,status -> report("CLIENT $id $name $status") }) {}
            report("SELECTED $id title=${info.title} ${info.quality} ${info.resolution} audio=${info.audioCodec}; fragments=${info.videoPlan?.segments?.size ?: 0}/${info.audioPlan?.segments?.size ?: 0}")
            assertNotNull(info.audio)
            if(requested==null) assertTrue(info.resolution,Regex("[0-9]+").findAll(info.resolution).map { it.value.toInt() }.minOrNull()!!>=2160)
            if(id=="t2dhvFTktk4") assertTrue(info.quality.contains("HDR"))
            if(full || InstrumentationRegistry.getArguments().getString("youtubeSamples")=="true") {
                val dir=java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"yt-samples-$id").apply { mkdirs() }
                try {
                    val sampleClient=OkHttpClient.Builder().callTimeout(60,TimeUnit.SECONDS).build()
                    suspend fun sample(url: String,plan: SegmentPlan?,name: String,ua: String): java.io.File {
                        val file=java.io.File(dir,name)
                        var nextReport=16*1024*1024L
                        val progress: (Long,Long,Long)->Unit={ bytes,_,_ -> if(bytes>=nextReport) { report("DOWNLOAD $id $name bytes=$bytes"); nextReport=bytes+16*1024*1024 } }
                        if(plan!=null) SegmentTransfer(sampleClient,{}).download(if(full) plan else plan.copy(segments=plan.segments.take(3)),file,ua,info.referer,progress)
                        else if(full) ResumableTransfer(sampleClient) {}.download(url,file,id+name,ua,info.referer,progress)
                        else sampleClient.newCall(Request.Builder().url(url).header("Range","bytes=0-4194303").header("User-Agent",ua).build()).execute().use {
                            check(it.isSuccessful); file.outputStream().use { out -> it.body!!.byteStream().copyTo(out) }
                        }
                        return file
                    }
                    val video=sample(info.video,info.videoPlan,"video",info.userAgent)
                    val audio=sample(info.audio!!,info.audioPlan,"audio",info.audioUserAgent ?: info.userAgent)
                    val out=java.io.File(dir,"sample.mp4")
                    LosslessMuxer.merge(video,audio,out,info.audioCodec)
                    fun probe(file: java.io.File): org.json.JSONObject {
                        val result=com.arthenica.ffmpegkit.FFprobeKit.executeWithArguments(arrayOf("-v","error","-show_streams","-show_format","-of","json",file.absolutePath))
                        check(com.arthenica.ffmpegkit.ReturnCode.isSuccess(result.returnCode)); return org.json.JSONObject(result.output)
                    }
                    fun videoTrack(json: org.json.JSONObject): org.json.JSONObject {
                        val tracks=json.getJSONArray("streams"); return (0 until tracks.length()).map { tracks.getJSONObject(it) }.first { it.optString("codec_type")=="video" }
                    }
                    val beforeJson=probe(video); val afterJson=probe(out)
                    val before=videoTrack(beforeJson); val after=videoTrack(afterJson)
                    val duration=afterJson.getJSONObject("format").getDouble("duration")
                    if(full) assertEquals(beforeJson.getJSONObject("format").getDouble("duration"),duration,1.0)
                    val tracks=afterJson.getJSONArray("streams")
                    val sound=(0 until tracks.length()).map { tracks.getJSONObject(it) }.first { it.optString("codec_type")=="audio" }
                    report("OUTPUT full=$full seconds=$duration videoCodec=${after.optString("codec_name")} fps=${after.optString("avg_frame_rate")} audio=${sound.optString("codec_name")} channels=${sound.optInt("channels")} sampleRate=${sound.optString("sample_rate")}")
                    for(key in listOf("codec_name","width","height","pix_fmt","color_space","color_transfer","color_primaries")) assertEquals(key,before.opt(key),after.opt(key))
                    if(info.quality.contains("HDR")) assertEquals("smpte2084",after.getString("color_transfer"))
                    report("MERGED $id ${after.optInt("width")}x${after.optInt("height")} ${after.optString("pix_fmt")} ${after.optString("color_transfer")} audio=${info.audioCodec}; bytes=${out.length()}")
                } finally { dir.deleteRecursively() }
            }
            val client=OkHttpClient.Builder().callTimeout(30,TimeUnit.SECONDS).build()
            listOf(info.videoPlan?.segments?.firstOrNull() ?: MediaSegment(info.video),
                info.audioPlan?.segments?.firstOrNull() ?: MediaSegment(info.audio!!)).forEachIndexed { index,part ->
                val end=part.start + if(part.length>0) minOf(part.length,32768)-1 else 32767
                client.newCall(Request.Builder().url(part.url).header("User-Agent",if(index==0) info.userAgent else info.audioUserAgent ?: info.userAgent)
                    .header("Referer",info.referer).header("Range","bytes=${part.start}-$end").build()).execute().use {
                    assertTrue("HTTP ${it.code}",it.isSuccessful)
                    assertTrue(it.body!!.byteStream().read(ByteArray(32))>0)
                }
            }
        }
    }
}
