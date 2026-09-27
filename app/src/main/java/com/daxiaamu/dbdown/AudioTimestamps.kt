package com.daxiaamu.dbdown

/** DASH fragment boundaries can round AAC timestamps a few microseconds backwards. */
class AudioTimestamps {
    private var previous = -1L
    fun next(timestampUs: Long): Long {
        require(timestampUs >= 0) { "音轨时间戳无效" }
        check(previous - timestampUs <= 1000L) { "音轨时间戳异常，无法无损合并此视频" }
        return maxOf(timestampUs, previous + 1).also { previous = it }
    }
}
