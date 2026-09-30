package com.daxiaamu.dbdown

internal data class DownloadResource(val key: String, val url: String, val userAgent: String, val plan: SegmentPlan? = null)

internal fun downloadResources(info: VideoInfo): List<DownloadResource> = buildList {
    if(info.images.isNotEmpty()) {
        info.images.forEachIndexed { index,url ->
            add(DownloadResource("image:$index",url,info.userAgent))
            info.imageVideos.getOrNull(index)?.let { add(DownloadResource("motion:$index",it,info.userAgent)) }
        }
        info.music?.let { add(DownloadResource("music",it,info.userAgent)) }
    } else {
        add(DownloadResource("video",info.video,info.userAgent,info.videoPlan))
        info.audio?.let { add(DownloadResource("audio",it,info.audioUserAgent ?: info.userAgent,info.audioPlan)) }
    }
}

/** Logical resource bytes, not network traffic or temporary/merged copies. */
internal class TaskTransferProgress(resources: List<DownloadResource>) {
    data class Snapshot(val bytes: Long, val total: Long)
    private data class Entry(var url: String, var size: Long, var bytes: Long = 0, var observed: Boolean = false, var finished: Boolean = false)
    private val entries = resources.associate { resource ->
        val parts=resource.plan?.segments
        val size=if(!parts.isNullOrEmpty() && parts.all { it.length >= 0 && it.keyUrl == null }) parts.sumOf { it.length } else -1L
        resource.key to Entry(resource.url,size)
    }
    @Synchronized fun begin(key: String, url: String) {
        val entry=entries.getValue(key)
        if(entry.url!=url) { entry.url=url; entry.size=-1; entry.bytes=0; entry.observed=false; entry.finished=false }
    }
    @Synchronized fun discovered(key: String, url: String, size: Long) {
        val entry=entries.getValue(key)
        if(size>=0 && entry.url==url && !entry.observed && !entry.finished) entry.size=maxOf(size,entry.bytes)
    }
    @Synchronized fun update(key: String, bytes: Long, length: Long) {
        val entry=entries.getValue(key)
        if(entry.finished) return
        entry.bytes=bytes.coerceAtLeast(0)
        if(length>=0) { entry.size=maxOf(length,entry.bytes); entry.observed=true }
        else if(entry.size>=0 && entry.bytes>entry.size) entry.size=-1
    }
    @Synchronized fun complete(key: String, size: Long) {
        val entry=entries.getValue(key)
        entry.bytes=size; entry.size=size; entry.observed=true; entry.finished=true
    }
    @Synchronized fun snapshot() = Snapshot(entries.values.sumOf { it.bytes },
        if(entries.values.all { it.size>=0 }) entries.values.sumOf { it.size } else -1L)
}
