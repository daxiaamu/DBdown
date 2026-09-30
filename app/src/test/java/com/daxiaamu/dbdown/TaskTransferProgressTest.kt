package com.daxiaamu.dbdown

import org.junit.Assert.*
import org.junit.Test

class TaskTransferProgressTest {
    private fun resource(key: String)=DownloadResource(key,"https://example.test/$key","test")
    @Test fun independentVideoAndAudioShareOneTotalThroughoutDownload() {
        val progress=TaskTransferProgress(listOf(resource("video"),resource("audio")))
        progress.discovered("video",resource("video").url,1000)
        progress.discovered("audio",resource("audio").url,100)
        progress.update("video",500,1000)
        assertEquals(TaskTransferProgress.Snapshot(500,1100),progress.snapshot())
        progress.complete("video",1000)
        progress.update("audio",50,100)
        assertEquals(TaskTransferProgress.Snapshot(1050,1100),progress.snapshot())
        progress.complete("audio",100)
        assertEquals(TaskTransferProgress.Snapshot(1100,1100),progress.snapshot())
    }
    @Test fun partialKnowledgeNeverPresentsFirstFileAsTaskTotal() {
        val progress=TaskTransferProgress(listOf(resource("image"),resource("music")))
        progress.update("image",100,100)
        progress.complete("image",100)
        assertEquals(TaskTransferProgress.Snapshot(100,-1),progress.snapshot())
        progress.update("music",10,200)
        assertEquals(TaskTransferProgress.Snapshot(110,300),progress.snapshot())
    }
    @Test fun liveAlbumIncludesEveryCoverMotionAndMusicExactlyOnce() {
        val link=Links.detect("https://www.douyin.com/video/123456789")!!
        val info=VideoInfo(link,"id","album","",referer=link.url,userAgent="test",
            images=listOf("cover1","cover2"),imageVideos=listOf("motion1","motion2"),music="music")
        val resources=downloadResources(info)
        assertEquals(listOf("image:0","motion:0","image:1","motion:1","music"),resources.map { it.key })
        val progress=TaskTransferProgress(resources)
        resources.forEachIndexed { i,r -> progress.complete(r.key,(i+1)*100L) }
        assertEquals(TaskTransferProgress.Snapshot(1500,1500),progress.snapshot())
    }
    @Test fun resumeUpdatesPositionInsteadOfAddingTransferredBytesAgain() {
        val progress=TaskTransferProgress(listOf(resource("video")))
        progress.update("video",60,100); progress.update("video",60,100); progress.update("video",80,100)
        assertEquals(TaskTransferProgress.Snapshot(80,100),progress.snapshot())
    }
    @Test fun lateHeadCannotOverwriteActualSizeOrFallbackUrl() {
        val progress=TaskTransferProgress(listOf(resource("video")))
        progress.update("video",10,200)
        progress.discovered("video",resource("video").url,100)
        assertEquals(200,progress.snapshot().total)
        progress.begin("video","https://example.test/fallback")
        progress.discovered("video",resource("video").url,100)
        assertEquals(-1,progress.snapshot().total)
        progress.complete("video",250)
        progress.discovered("video","https://example.test/fallback",200)
        assertEquals(TaskTransferProgress.Snapshot(250,250),progress.snapshot())
    }
    @Test fun rangedVideoAndAudioCountAllSegmentsWithoutCountingMerge() {
        val video=resource("video").copy(plan=SegmentPlan(listOf(MediaSegment("v",0,40),MediaSegment("v",40,60)),"v"))
        val audio=resource("audio").copy(plan=SegmentPlan(listOf(MediaSegment("a",0,10)),"a"))
        val progress=TaskTransferProgress(listOf(video,audio))
        assertEquals(110,progress.snapshot().total)
        progress.complete("video",100); progress.complete("audio",10)
        assertEquals(TaskTransferProgress.Snapshot(110,110),progress.snapshot())
    }
}
