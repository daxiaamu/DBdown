package com.daxiaamu.dbdown

import android.app.Application
import com.daxiaamu.dbdown.update.UpdateManager

class DownloaderApp : Application() {
    lateinit var updates: UpdateManager
        private set
    lateinit var store: DownloadStore
        private set

    override fun onCreate() {
        super.onCreate()
        WebAccounts.initialize(this)
        DouyinDesktop.initialize(this)
        store = DownloadStore(this)
        updates = UpdateManager(this)
    }
}
