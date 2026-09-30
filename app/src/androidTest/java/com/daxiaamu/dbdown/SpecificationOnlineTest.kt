package com.daxiaamu.dbdown

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SpecificationOnlineTest {
    private fun report(text: String) = InstrumentationRegistry.getInstrumentation().sendStatus(0,
        android.os.Bundle().apply { putString("stream",text+"\n") })
    @Test fun selectedBiliVideoAndAudioDownloadAndMerge() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<DownloaderApp>()
        val link=Links.detect("https://www.bilibili.com/video/BV1zjap6kEFk?p=1")!!
        val initial=VideoResolver().resolve(link)
        val specs=initial.specifications!!
        val choice=TrackSelection(specs.videos.last().id,specs.audios.first().id)
        val chosen=VideoResolver().resolve(link,choice)
        assertEquals(choice,chosen.specifications!!.selected)
        report("Bili selected: ${chosen.resolution}, ${chosen.fps} fps, ${chosen.audioCodec}")
        val dir=File(context.cacheDir,"specification-mux-test").apply { mkdirs() }
        try {
            withContext(Dispatchers.IO) {
                val client=VideoResolver.client.newBuilder().callTimeout(0,TimeUnit.SECONDS).build()
                val video=File(dir,"video"); val audio=File(dir,"audio"); val out=File(dir,"merged.mp4")
                val transfer=ResumableTransfer(client) {}
                transfer.download(chosen.video,video,"video",chosen.userAgent,chosen.referer) { _,_,_ -> }
                transfer.download(chosen.audio!!,audio,"audio",chosen.audioUserAgent ?: chosen.userAgent,chosen.referer) { _,_,_ -> }
                LosslessMuxer.merge(video,audio,out,if(chosen.audioCodec.startsWith("mp4a")) "aac" else chosen.audioCodec)
                android.media.MediaExtractor().let { extractor ->
                    try {
                        extractor.setDataSource(out.path)
                        val formats=(0 until extractor.trackCount).map(extractor::getTrackFormat)
                        assertTrue(formats.any { it.getString("mime")?.startsWith("audio/") == true })
                        val format=formats.first { it.getString("mime")?.startsWith("video/") == true }
                        assertEquals(chosen.resolution,resolutionLabel(format.getInteger("width"),format.getInteger("height")))
                        report("Bili merged and verified both tracks; size=${out.length()}")
                    } finally { extractor.release() }
                }
            }
        } finally { dir.deleteRecursively() }
    }
    @Test fun youtubeSelectionResolvesAnExplicitVideoAudioPair() = runBlocking {
        val link=Links.detect("https://www.youtube.com/watch?v=IkIxTn6OiWE")!!
        val initial=VideoResolver().resolve(link)
        val specs=initial.specifications!!
        val choice=TrackSelection(specs.videos.last { !it.hasAudio }.id,specs.audios.first().id)
        val selected=VideoResolver().resolve(link,choice)
        assertEquals(choice,selected.specifications!!.selected)
        assertNotNull(selected.audio)
        assertTrue(selected.video != selected.audio)
        report("YouTube explicit pair verified: ${selected.resolution}, ${selected.fps} fps, ${selected.audioCodec}")
    }
}
