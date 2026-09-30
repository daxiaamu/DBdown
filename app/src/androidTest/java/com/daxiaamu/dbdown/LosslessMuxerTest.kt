package com.daxiaamu.dbdown

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LosslessMuxerTest {
    @Test fun preservesFlacPacketsAndHiResFormatInMp4() = runBlocking {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "flac-test-${System.nanoTime()}")
        check(dir.mkdirs())
        try {
            val video = File(dir, "video with spaces.mp4")
            val audio = File(dir, "audio.flac")
            val output = File(dir, "output.mp4")
            fun execute(vararg args: String) {
                val session = FFmpegKit.executeWithArguments(arrayOf("-y", "-v", "error") + args)
                assertTrue(session.output, ReturnCode.isSuccess(session.returnCode))
            }
            execute("-f", "lavfi", "-i", "color=c=blue:s=160x90:r=10", "-t", "1", "-an", "-c:v", "mpeg4", video.absolutePath)
            execute("-f", "lavfi", "-i", "sine=frequency=1000:sample_rate=96000", "-t", "1",
                "-ac", "2", "-sample_fmt", "s32", "-c:a", "flac", audio.absolutePath)
            LosslessMuxer.merge(video, audio, output)
            fun probe(file: File): JSONObject {
                val result = FFprobeKit.executeWithArguments(arrayOf("-v", "error", "-select_streams", "a:0",
                    "-show_streams", "-show_packets", "-show_data_hash", "sha256", "-of", "json", file.absolutePath))
                assertTrue(ReturnCode.isSuccess(result.returnCode))
                return JSONObject(result.output)
            }
            val before = probe(audio)
            val after = probe(output)
            val stream = after.getJSONArray("streams").getJSONObject(0)
            assertEquals("flac", stream.getString("codec_name"))
            assertEquals("96000", stream.getString("sample_rate"))
            assertEquals(2, stream.getInt("channels"))
            assertEquals(24, stream.getInt("bits_per_raw_sample"))
            fun hashes(json: JSONObject): List<String> {
                val packets = json.getJSONArray("packets")
                return (0 until packets.length()).map { packets.getJSONObject(it).getString("data_hash") }
            }
            assertTrue(hashes(before).isNotEmpty())
            assertEquals(hashes(before), hashes(after))
        } finally { dir.deleteRecursively() }
    }
    @Test fun explicitlySilentVideoRemuxesWithoutAddingAudio() = runBlocking {
        val dir=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"silent-test-${System.nanoTime()}")
        check(dir.mkdirs())
        try {
            val input=File(dir,"silent.mkv")
            val output=File(dir,"silent.mp4")
            val created=FFmpegKit.executeWithArguments(arrayOf("-y","-v","error","-f","lavfi",
                "-i","color=c=blue:s=160x90:r=10","-t","1","-an","-c:v","mpeg4",input.path))
            assertTrue(created.output,ReturnCode.isSuccess(created.returnCode))
            LosslessMuxer.remux(input,output,requireAudio=false)
            val result=FFprobeKit.executeWithArguments(arrayOf("-v","error","-show_streams","-of","json",output.path))
            assertTrue(ReturnCode.isSuccess(result.returnCode))
            val tracks=JSONObject(result.output).getJSONArray("streams")
            assertEquals(1,tracks.length())
            assertEquals("video",tracks.getJSONObject(0).getString("codec_type"))
        } finally { dir.deleteRecursively() }
    }

}
