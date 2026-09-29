package com.daxiaamu.dbdown

/** Divide the full music duration equally; distribute leftover milliseconds without truncation. */
internal fun albumImageDurations(count: Int, musicDurationMs: Long): List<Long> {
    require(count > 0 && musicDurationMs >= count) { "配乐时长不足以展示全部图片" }
    return List(count) { index -> musicDurationMs / count + if(index.toLong() < musicDurationMs % count) 1L else 0L }
}

internal fun effectiveAlbumMode(requested: AlbumMode, hasImages: Boolean, music: String?): AlbumMode =
    if(!hasImages || music.isNullOrBlank()) AlbumMode.IMAGES else requested
