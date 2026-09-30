package com.daxiaamu.dbdown

import java.util.UUID

enum class AlbumMode { IMAGES, VIDEO }

enum class TaskStatus(val label: String) {
    PAUSED("已暂停"), QUEUED("等待下载"), RESOLVING("正在解析"), DOWNLOADING("正在下载"), MERGING("正在合成"),
    SAVING("保存到相册"), COMPLETED("已完成"), FAILED("下载失败"), CANCELLED("已取消"), INTERRUPTED("下载中断");
    val pending get() = active || this == PAUSED
    val active get() = this in setOf(QUEUED, RESOLVING, DOWNLOADING, MERGING, SAVING)
}
data class DownloadTask(
    val id: String = UUID.randomUUID().toString(), val source: String, val key: String,
    val platform: Platform, val title: String = "正在获取视频信息",
    val status: TaskStatus = TaskStatus.QUEUED, val bytes: Long = 0, val total: Long = -1,
    val speed: Long = 0, val quality: String = "", val uri: String = "", val error: String = "",
    val created: Long = System.currentTimeMillis(), val albumMode: AlbumMode = AlbumMode.IMAGES,
    val outputUris: List<String> = emptyList(), val mimeType: String = "video/mp4", val resolution: String = ""
) {
    val progress: Float get() = if(total > 0) (bytes.toDouble()/total).toFloat().coerceIn(0f, 1f) else 0f
}
