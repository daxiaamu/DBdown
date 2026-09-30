package com.daxiaamu.dbdown

import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.VideoStream

/** Translate extractor-specific models once; all sources use the same selection rules afterward. */
internal fun youtubeExtractorAudio(audio: AudioStream): YoutubeStream? {
    if(!audio.isUrl || audio.deliveryMethod != DeliveryMethod.PROGRESSIVE_HTTP ||
        (audio.format != MediaFormat.M4A && !audio.codec.orEmpty().startsWith("opus"))) return null
    return YoutubeStream(audio.content, false, true, audio.codec.orEmpty(),
        bitrate = if(audio.averageBitrate > 0) audio.averageBitrate * 1000L else audio.bitrate.toLong(),
        language = audio.audioTrackId ?: audio.audioLocale?.toLanguageTag() ?: audio.audioTrackType?.name.orEmpty(),
        original = audio.audioTrackType == AudioTrackType.ORIGINAL, channels = audio.itagItem?.audioChannels ?: 0,
        formatId = audio.itag.toString(), source = "NewPipe formatted")
}

internal fun youtubeExtractorVideo(video: VideoStream): YoutubeStream? {
    if(!video.isUrl || video.deliveryMethod != DeliveryMethod.PROGRESSIVE_HTTP ||
        video.format !in setOf(MediaFormat.MPEG_4, MediaFormat.WEBM) ||
        listOf("avc", "hev", "hvc", "av01", "vp9", "vp09").none(video.codec.orEmpty()::startsWith)) return null
    return YoutubeStream(video.content, true, !video.isVideoOnly(), video.codec.orEmpty(), video.width, video.height,
        video.fps.toFloat(), bitrate = video.bitrate.toLong(), formatId = video.itag.toString(), source = "NewPipe formatted")
}
