package com.daxiaamu.dbdown

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Explicit opt-in: these checks depend on YouTube availability and are not part of offline CI. */
class YoutubeOnlineTest {
    @Test fun requestedVideo() {
        val input=System.getenv("DBDOWN_YOUTUBE_INPUT")
        assumeTrue(!input.isNullOrBlank())
        check(input!!)
    }
    @Test fun reportedHdrSample() {
        check("https://www.youtube.com/watch?v=t2dhvFTktk4", true)
        val rawPlayers = mutableListOf<org.json.JSONObject>()
        val delegate = org.schabi.newpipe.extractor.NewPipe.getDownloader()
        org.schabi.newpipe.extractor.NewPipe.init(object : org.schabi.newpipe.extractor.downloader.Downloader() {
            override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): org.schabi.newpipe.extractor.downloader.Response {
                val response = delegate.execute(request)
                if(request.url().contains("/player")) {
                    val data = runCatching { org.json.JSONObject(response.responseBody()) }.getOrNull()
                    if(data != null) rawPlayers.add(data)
                    val formats = data?.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats")
                    println("Raw host=${java.net.URI(request.url()).host} player ${data?.optJSONObject("videoDetails")?.optString("videoId")} status=${data?.optJSONObject("playabilityStatus")?.optString("status")} formats=${formats?.length()}")
                    if(formats != null) for(index in 0 until formats.length()) {
                        val item = formats.getJSONObject(index)
                        println("Raw ${listOf("itag","mimeType","width","height","fps","bitrate","qualityLabel","colorInfo","audioChannels").associateWith { item.opt(it) }}")
                    }
                }
                return response
            }
        })
        try {
        val extractor = org.schabi.newpipe.extractor.ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=t2dhvFTktk4")
        extractor.fetchPage()
        (extractor.videoStreams + extractor.videoOnlyStreams).forEach {
            println("Video itag=${it.itag} ${it.width}x${it.height} fps=${it.fps} codec=${it.codec} bitrate=${it.bitrate} format=${it.format} delivery=${it.deliveryMethod}")
        }
        extractor.audioStreams.forEach {
            println("Audio itag=${it.itag} codec=${it.codec} rate=${it.averageBitrate}/${it.bitrate} format=${it.format} channels=${it.itagItem?.audioChannels} track=${it.audioTrackType}")
        }
        mergeYoutubePlayers(rawPlayers,Links.detect("t2dhvFTktk4")!!)?.let { player ->
            val candidate = youtubePlayerVideo(player,Links.detect("t2dhvFTktk4")!!)!!
            println("RAW CHOICE quality=${candidate.quality} audio=${candidate.audioCodec}")
            val client = OkHttpClient.Builder().callTimeout(30,TimeUnit.SECONDS).build()
            listOfNotNull(candidate.video,candidate.audio).forEach { url ->
                client.newCall(Request.Builder().url(url).header("Range","bytes=0-0").header("User-Agent",candidate.userAgent).header("Referer",candidate.referer).build()).execute().use { println("RAW MEDIA ${it.code}") }
            }
        }
        } finally { org.schabi.newpipe.extractor.NewPipe.init(delegate) }
    }
    @Test fun reported4kSample() = check("https://www.youtube.com/watch?v=b-Ag7meqZoU", true)
    @Test fun reportedTitleSample() = check("https://youtu.be/9j_gaUAT2yc?is=BoppiEk0ARCBg8aC")
    @Test fun normalVideo() = check("qIzGvexMjpA")
    @Test fun shorts() = check("https://www.youtube.com/shorts/-9OM3w3TWUs")
    @Test fun bareId() = check("BLKegH19KGI")
    private fun check(input: String, require4k: Boolean = false) {
        assumeTrue(System.getenv("DBDOWN_YOUTUBE_ONLINE") == "1")
        val info = YoutubeResolver.resolve(Links.detect(input)!!) {}
        if(info.source.key == "yt:t2dhvFTktk4") {
            assertTrue(info.quality,info.quality.contains("HDR"))
            assertEquals("opus",info.audioCodec)
        }
        println("YouTube ${info.source.key}: ${info.title}; ${info.resolution}; quality=${info.quality}; audioCodec=${info.audioCodec}; separateAudio=${info.audio != null}")
        assertTrue(info.title.isNotBlank())
        assertTrue(info.resolution.isNotBlank())
        if(require4k) {
            assertNotNull(info.audio)
            assertTrue(info.resolution, Regex("[0-9]+").findAll(info.resolution).map { it.value.toInt() }.minOrNull()!! >= 2160)
        }
        val fixtureDir = System.getenv("DBDOWN_YOUTUBE_FIXTURES")?.let { java.io.File(it).apply { mkdirs() } }
        val client = OkHttpClient.Builder().callTimeout(45, TimeUnit.SECONDS).build()
        listOfNotNull(info.video, info.audio).forEachIndexed { index, url ->
            client.newCall(Request.Builder().url(url).header("Range", if(fixtureDir == null) "bytes=0-65535" else "bytes=0-4194303")
                .header("User-Agent", info.userAgent).header("Referer", info.referer).build()).execute().use {
                assertTrue("Media HTTP ${it.code}", it.isSuccessful)
                if(fixtureDir != null) {
                    java.io.File(fixtureDir, if(index == 0) "video" else "audio").outputStream().use { out -> it.body!!.byteStream().copyTo(out) }
                    java.io.File(fixtureDir, "audio-codec").writeText(info.audioCodec)
                } else {
                    val bytes = ByteArray(32)
                    assertTrue(it.body!!.byteStream().read(bytes) > 0)
                }
                println("Media HTTP ${it.code}; type=${it.body!!.contentType()}")
            }
        }
    }
}
