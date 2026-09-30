package com.daxiaamu.dbdown

import android.app.Application
import com.daxiaamu.dbdown.update.UpdateManager

class DownloaderApp : Application() {
    lateinit var updates: UpdateManager
        private set
    lateinit var store: DownloadStore
        private set

    private val preparedDownloads = mutableMapOf<String,Pair<Long,VideoInfo>>()
    internal fun prepareDownload(id: String, info: VideoInfo) {
        val now = android.os.SystemClock.elapsedRealtime()
        preparedDownloads.entries.removeAll { now-it.value.first > 120_000 }
        preparedDownloads[id] = now to info
    }
    internal fun takePreparedDownload(id: String): VideoInfo? = preparedDownloads.remove(id)?.takeIf {
        android.os.SystemClock.elapsedRealtime()-it.first <= 120_000
    }?.second

    override fun onCreate() {
        super.onCreate()
        WebAccounts.initialize(this)
        DouyinDesktop.initialize(this)
        store = DownloadStore(this)
        updates = UpdateManager(this)
    }
}
