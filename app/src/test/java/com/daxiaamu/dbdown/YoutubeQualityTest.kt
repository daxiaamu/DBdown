package com.daxiaamu.dbdown
import org.junit.Assert.*
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.stream.*

class YoutubeQualityTest {
    private fun video(width: Int, height: Int, codec: String = "avc1.640028", only: Boolean = true,
                      delivery: DeliveryMethod = DeliveryMethod.PROGRESSIVE_HTTP, format: MediaFormat = MediaFormat.MPEG_4): VideoStream {
        val itag = ItagItem(137, if(only) ItagItem.ItagType.VIDEO_ONLY else ItagItem.ItagType.VIDEO,
            format, "${minOf(width,height)}p").apply { setWidth(width); setHeight(height); setCodec(codec) }
        return VideoStream.Builder().setId("$width-$height-$codec").setContent("https://example.com/video-$width-$height-$codec-$only", true)
            .setMediaFormat(format).setIsVideoOnly(only).setResolution("${minOf(width,height)}p")
            .setDeliveryMethod(delivery).setItagItem(itag).build()
    }
    private fun audio(rate: Int, type: AudioTrackType = AudioTrackType.ORIGINAL,
        format: MediaFormat = MediaFormat.M4A, delivery: DeliveryMethod = DeliveryMethod.PROGRESSIVE_HTTP,
        codec: String = if(format in setOf(MediaFormat.WEBMA, MediaFormat.WEBMA_OPUS)) "opus" else "mp4a.40.2") =
        AudioStream.Builder().setId("$rate-$type").setContent("https://example.com/audio-$rate-$type-$codec", true)
            .setItagItem(ItagItem(251, ItagItem.ItagType.AUDIO, format, rate).apply { setCodec(codec) })
            .setMediaFormat(format).setAverageBitrate(rate).setAudioTrackType(type).setDeliveryMethod(delivery).build()
    private fun selectedVideo(videos: List<VideoStream>, hasAudio: Boolean = true): YoutubeStream? =
        youtubeSelectStreams(videos.mapNotNull(::youtubeExtractorVideo) +
            if(hasAudio) listOfNotNull(youtubeExtractorAudio(audio(128))) else emptyList()) { it }?.first
    private fun selectedAudio(audios: List<AudioStream>): YoutubeStream? =
        youtubeSelectStreams(listOfNotNull(youtubeExtractorVideo(video(1280,720,only=false))) +
            audios.mapNotNull(::youtubeExtractorAudio)) { it }?.second

    @Test fun picksHighestOriginalAudioRatherThanHigherBitrateDub() {
        val high = audio(256)
        assertEquals(youtubeExtractorAudio(high), selectedAudio(listOf(audio(128), high, audio(384, AudioTrackType.DUBBED))))
    }
    @Test fun acceptsOpusButSkipsSegmentedAndUnsupportedAudio() {
        val supported = audio(320, format = MediaFormat.WEBMA)
        assertEquals(youtubeExtractorAudio(supported), selectedAudio(listOf(supported, audio(256, delivery = DeliveryMethod.HLS),
            audio(128), audio(500, format = MediaFormat.WEBMA, codec = "vorbis"))))
        assertNull(selectedAudio(emptyList()))
    }
    @Test fun choosesHighestCompatibleResolution() {
        val high = video(7680,4320,"av01")
        assertEquals(youtubeExtractorVideo(high), selectedVideo(listOf(video(1920,1080),high,video(3840,2160,"hvc1"))))
    }
    @Test fun realNewpipeOpusEnumIsNotFilteredOut() {
        val opus = audio(160,format=MediaFormat.WEBMA_OPUS)
        assertEquals(youtubeExtractorAudio(opus), selectedAudio(listOf(audio(128),opus)))
    }
    @Test fun webm4kBeatsAvc1080() {
        val high = video(3840,2160,"vp9",format=MediaFormat.WEBM)
        assertEquals(youtubeExtractorVideo(high), selectedVideo(listOf(video(1920,1080),high)))
    }
    @Test fun missingAudioFallsBackToCombinedStream() {
        val combined = video(1280,720,only=false)
        assertEquals(youtubeExtractorVideo(combined), selectedVideo(listOf(video(1920,1080),combined),false))
        assertNull(selectedVideo(listOf(video(1920,1080)),false))
    }
    @Test fun skipsSegmentManifestsAndKeepsPortraitDimensions() {
        val portrait = video(1080,1920)
        assertEquals(youtubeExtractorVideo(portrait), selectedVideo(listOf(portrait,video(3840,2160,delivery=DeliveryMethod.HLS))))
        assertEquals("1080 × 1920", resolutionLabel(portrait.width,portrait.height))
    }
}
