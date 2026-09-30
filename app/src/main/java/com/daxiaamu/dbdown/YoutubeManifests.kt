package com.daxiaamu.dbdown

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.dash.manifest.Representation
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Parse finite clear/AES-128 VOD tracks; never hand a remote manifest to native FFmpeg. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class YoutubeManifests(private val read: (String, String) -> ByteArray,
    private val allowed: (String) -> Boolean = ::youtubeMediaUrl) {
    private val masters = mutableMapOf<String,HlsMultivariantPlaylist>()
    private fun safe(url: String) = url.also { require(allowed(it)) { "Unsupported media host" } }
    private fun track(url: String, f: Format, video: Boolean, audio: Boolean, ua: String, source: String,
        protocol: YoutubeProtocol, plan: SegmentPlan? = null): YoutubeStream {
        val codecs = f.codecs.orEmpty()
        val mime = (f.sampleMimeType ?: if(video) "video/mp4" else "audio/mp4") + "; codecs=\"$codecs\""
        return YoutubeStream(safe(url),video,audio,mime,f.width.coerceAtLeast(0),f.height.coerceAtLeast(0),
            f.frameRate.coerceAtLeast(0f),f.colorInfo?.colorTransfer in listOf(C.COLOR_TRANSFER_ST2084,C.COLOR_TRANSFER_HLG),
            (if(f.averageBitrate > 0) f.averageBitrate else f.peakBitrate).coerceAtLeast(0).toLong(),
            f.language.orEmpty(),f.roleFlags and C.ROLE_FLAG_MAIN != 0,f.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
            f.channelCount.coerceAtLeast(0),ua,source,f.id.orEmpty(),protocol,plan)
    }
    fun dash(url: String, ua: String, source: String): List<YoutubeStream> {
        val raw = read(safe(url),ua)
        require(!raw.toString(Charsets.UTF_8).contains("<!DOCTYPE",true))
        val manifest = DashManifestParser().parse(Uri.parse(url),raw.inputStream())
        require(!manifest.dynamic && manifest.periodCount == 1) { "Only single-period DASH VOD is supported" }
        val duration = manifest.getPeriodDurationUs(0)
        val result = mutableListOf<YoutubeStream>()
        for(adaptation in manifest.getPeriod(0).adaptationSets) for(rep in adaptation.representations) {
            if(rep.format.drmInitData != null || adaptation.type !in listOf(C.TRACK_TYPE_VIDEO,C.TRACK_TYPE_AUDIO)) continue
            val index = rep.index
            for(base in rep.baseUrls) {
                val baseUrl = safe(base.url)
                if(rep is Representation.SingleSegmentRepresentation || index == null) {
                    // SegmentBase points to a complete file; no sidx slicing is necessary when downloading it all.
                    result += track(baseUrl,rep.format,adaptation.type == C.TRACK_TYPE_VIDEO,
                        adaptation.type == C.TRACK_TYPE_AUDIO,ua,source,YoutubeProtocol.HTTP)
                    continue
                }
                val count = index.getSegmentCount(duration)
                if(count !in 1..100000L) continue
                val parts = mutableListOf<MediaSegment>()
                rep.initializationUri?.let { parts += MediaSegment(safe(it.resolveUriString(baseUrl)),it.start,it.length) }
                val first = index.firstSegmentNum
                for(number in first until first + count) {
                    val item = index.getSegmentUrl(number)
                    parts += MediaSegment(safe(item.resolveUriString(baseUrl)),item.start,item.length)
                }
                result += track(baseUrl,rep.format,adaptation.type == C.TRACK_TYPE_VIDEO,
                    adaptation.type == C.TRACK_TYPE_AUDIO,ua,source,YoutubeProtocol.DASH,
                    SegmentPlan(parts,"dash:${rep.format.id}"))
            }
        }
        return result
    }
    private fun hlsBytes(url: String, ua: String): ByteArray {
        val bytes = read(safe(url),ua)
        for(line in bytes.toString(Charsets.UTF_8).lineSequence().map(String::trim)) {
            if(!line.startsWith("#EXT-X-KEY:") && !line.startsWith("#EXT-X-SESSION-KEY:")) continue
            val fields = line.substringAfter(':')
            val method = Regex("(?:^|,)METHOD=([^,]+)").find(fields)?.groupValues?.get(1)
            val format = Regex("KEYFORMAT=\"([^\"]+)\"").find(fields)?.groupValues?.get(1)
            require(method == "NONE" || (method == "AES-128" && (format == null || format == "identity") &&
                !line.startsWith("#EXT-X-SESSION-KEY:"))) { "Unsupported HLS encryption" }
        }
        return bytes
    }
    fun hls(url: String, ua: String, source: String): List<YoutubeStream> {
        val bytes = hlsBytes(url,ua)
        val playlist = HlsPlaylistParser().parse(Uri.parse(safe(url)),bytes.inputStream())
        if(playlist is HlsMediaPlaylist) {
            return listOf(YoutubeStream(url,true,true,"video/mp4",userAgent=ua,source=source,
                protocol=YoutubeProtocol.HLS,plan=hlsPlan(url,playlist)))
        }
        require(playlist is HlsMultivariantPlaylist)
        if(playlist.sessionKeyDrmInitData.isNotEmpty()) return emptyList()
        // Media3 1.9 does not propagate VIDEO-RANGE into Format.colorInfo for every codec.
        val hdrVariants = mutableSetOf<String>()
        var range = ""
        for(line in bytes.toString(Charsets.UTF_8).lineSequence().map(String::trim)) {
            if(line.startsWith("#EXT-X-STREAM-INF:")) range = Regex("VIDEO-RANGE=(PQ|HLG)").find(line)?.value.orEmpty()
            else if(line.isNotEmpty() && !line.startsWith('#')) {
                if(range.isNotEmpty()) url.toHttpUrl().resolve(line)?.toString()?.let(hdrVariants::add)
                range = ""
            }
        }
        val result = mutableListOf<YoutubeStream>()
        for(v in playlist.variants) {
            if(v.format.drmInitData != null) continue
            val target = safe(v.url.toString())
            masters[target] = playlist
            val externalAudio = playlist.audios.any { it.groupId == v.audioGroupId && it.url != null }
            val audio = !externalAudio && (MimeTypes.getAudioMediaMimeType(v.format.codecs.orEmpty()) != null || playlist.muxedAudioFormat != null)
            result += track(target,v.format,true,audio,ua,source,YoutubeProtocol.HLS).let { it.copy(hdr=it.hdr || target in hdrVariants) }
        }
        for(a in playlist.audios) {
            val target = a.url?.toString()?.let(::safe) ?: continue
            if(a.format.drmInitData != null) continue
            masters[target] = playlist
            result += track(target,a.format,false,true,ua,source,YoutubeProtocol.HLS)
        }
        return result
    }
    fun prepare(stream: YoutubeStream): YoutubeStream {
        if(stream.protocol != YoutubeProtocol.HLS || stream.plan != null) return stream
        val parser = masters[stream.url]?.let { HlsPlaylistParser(it,null) } ?: HlsPlaylistParser()
        val playlist = parser.parse(Uri.parse(stream.url),hlsBytes(stream.url,stream.userAgent).inputStream())
        require(playlist is HlsMediaPlaylist)
        return stream.copy(plan=hlsPlan(stream.url,playlist),durationMs=playlist.durationUs / 1000)
    }
    private fun hlsPlan(url: String, playlist: HlsMediaPlaylist): SegmentPlan {
        require(playlist.hasEndTag && playlist.segments.isNotEmpty() && playlist.segments.size <= 100000) { "HLS stream is not finite VOD" }
        require(playlist.protectionSchemes == null) { "DRM media is not supported" }
        val base = url.toHttpUrl()
        fun part(segment: HlsMediaPlaylist.Segment): MediaSegment {
            require(segment.drmInitData == null && !segment.hasGapTag) { "Incomplete or protected HLS stream" }
            return MediaSegment(safe(base.resolve(segment.url)!!.toString()),segment.byteRangeOffset,segment.byteRangeLength,
                segment.fullSegmentEncryptionKeyUri?.let { safe(base.resolve(it)!!.toString()) },segment.encryptionIV)
        }
        val parts = mutableListOf<MediaSegment>()
        var initialization: HlsMediaPlaylist.Segment? = null
        val discontinuity = playlist.segments.first().relativeDiscontinuitySequence
        for(segment in playlist.segments) {
            // Timestamp reset across discontinuities needs a timeline-aware remux; do not produce a truncated file.
            require(segment.relativeDiscontinuitySequence == discontinuity) { "HLS discontinuity is not supported" }
            if(segment.initializationSegment != null && segment.initializationSegment != initialization) {
                require(initialization == null) { "HLS initialization changed" }
                initialization = segment.initializationSegment
                parts += part(initialization!!)
            }
            parts += part(segment)
        }
        return SegmentPlan(parts,"hls:${playlist.mediaSequence}")
    }
}
