package com.daxiaamu.dbdown

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

/** Copy original video/audio packets, including AV1, VP9, Opus and FLAC, into MP4. */
internal object LosslessMuxer {
    internal fun arguments(video: File, audio: File, output: File) = arrayOf(
        "-nostdin", "-y", "-v", "error",
        "-i", video.absolutePath, "-i", audio.absolutePath,
        "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy", "-c:a", "copy",
        "-strict", "experimental", "-movflags", "+faststart", "-f", "mp4", output.absolutePath
    )

    suspend fun merge(video: File, audio: File, output: File, expectedAudioCodec: String = "flac") =
        run(arguments(video, audio, output), output, expectedAudioCodec)

    suspend fun remux(input: File, output: File, requireAudio: Boolean = true) = run(arrayOf(
        "-nostdin", "-y", "-v", "error", "-i", input.absolutePath,
        "-map", "0:v:0", "-map", if(requireAudio) "0:a:0" else "0:a:0?", "-c", "copy", "-strict", "experimental",
        "-movflags", "+faststart", "-f", "mp4", output.absolutePath
    ), output, null, requireAudio)

    private suspend fun run(arguments: Array<String>, output: File, expectedAudioCodec: String?, requireAudio: Boolean = true) = withContext(Dispatchers.IO) {
        val complete = CompletableDeferred<FFmpegSession>()
        val session = FFmpegKit.executeWithArgumentsAsync(arguments) { complete.complete(it) }
        try {
            val finished = complete.await()
            check(ReturnCode.isSuccess(finished.returnCode) && output.length() > 0) { "原始音视频合并失败，请重试" }
            currentCoroutineContext().ensureActive()
            val probe = FFprobeKit.executeWithArguments(arrayOf("-v", "error", "-show_streams", "-of", "json", output.absolutePath))
            check(ReturnCode.isSuccess(probe.returnCode)) { "无法校验合并后的视频文件" }
            val streams = JSONObject(probe.output).getJSONArray("streams")
            val types = (0 until streams.length()).map { streams.getJSONObject(it) }
            check(types.any { it.optString("codec_type") == "video" } &&
                (!requireAudio || types.any { it.optString("codec_type") == "audio" && (expectedAudioCodec == null || it.optString("codec_name") == expectedAudioCodec) })) {
                "合并结果没有保留原始音轨"
            }
        } finally {
            if(!complete.isCompleted) {
                FFmpegKit.cancel(session.sessionId)
                // Do not let task cleanup delete inputs while native code still owns them.
                withContext(NonCancellable) { complete.await() }
            }
        }
    }
}
