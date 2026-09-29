package com.daxiaamu.dbdown
import org.junit.Assert.*
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.stream.*

class YoutubeQualityTest {
    private fun video(width: Int, height: Int, codec: String = "avc1.640028", only: Boolean = true,
                      delivery: DeliveryMethod = DeliveryMethod.PROGRESSIVE_HTTP): VideoStream {
        val itag = ItagItem(137, if(only) ItagItem.ItagType.VIDEO_ONLY else ItagItem.ItagType.VIDEO,
            MediaFormat.MPEG_4, "${minOf(width,height)}p").apply { setWidth(width); setHeight(height); setCodec(codec) }
        return VideoStream.Builder().setId("$width-$height-$codec").setContent("https://example.com/video", true)
            .setMediaFormat(MediaFormat.MPEG_4).setIsVideoOnly(only).setResolution("${minOf(width,height)}p")
            .setDeliveryMethod(delivery).setItagItem(itag).build()
    }
    private fun audio(rate: Int, type: AudioTrackType = AudioTrackType.ORIGINAL,
        format: MediaFormat = MediaFormat.M4A, delivery: DeliveryMethod = DeliveryMethod.PROGRESSIVE_HTTP) =
        AudioStream.Builder().setId("$rate-$type").setContent("https://example.com/audio", true)
            .setMediaFormat(format).setAverageBitrate(rate).setAudioTrackType(type).setDeliveryMethod(delivery).build()
    @Test fun picksHighestOriginalAudioRatherThanHigherBitrateDub() {
        val high = audio(256)
        assertSame(high, bestYoutubeAudio(listOf(audio(128), high, audio(384, AudioTrackType.DUBBED))))
    }
    @Test fun ignoresUnsupportedAudioContainersAndSegmentedStreams() {
        val supported = audio(128)
        assertSame(supported, bestYoutubeAudio(listOf(supported, audio(256, delivery = DeliveryMethod.HLS),
            audio(320, format = MediaFormat.WEBMA))))
        assertNull(bestYoutubeAudio(emptyList()))
    }
    @Test fun choosesHighestCompatibleResolution() {
        val high = video(3840,2160,"hvc1")
        assertSame(high,bestYoutubeVideo(listOf(video(1920,1080),high,video(7680,4320,"av01")),true))
    }
    @Test fun missingAudioFallsBackToCombinedStream() {
        val combined = video(1280,720,only=false)
        assertSame(combined,bestYoutubeVideo(listOf(video(1920,1080),combined),false))
        assertNull(bestYoutubeVideo(listOf(video(1920,1080)),false))
    }
    @Test fun skipsSegmentManifestsAndKeepsPortraitDimensions() {
        val portrait = video(1080,1920)
        assertSame(portrait,bestYoutubeVideo(listOf(portrait,video(3840,2160,delivery=DeliveryMethod.HLS)),true))
        assertEquals("1080 × 1920",resolutionLabel(portrait.width,portrait.height))
    }
}
