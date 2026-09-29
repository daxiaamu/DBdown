package com.daxiaamu.dbdown

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException

internal class MediaHttpException(val code: Int) : IOException("下载服务器返回 $code，请重试以刷新视频地址")

internal suspend fun downloadWithFallback(
    urls: List<String>, refreshOnUnavailable: (suspend () -> List<String>)? = null,
    transfer: suspend (String) -> Unit
) {
    require(urls.isNotEmpty())
    var failure: Exception? = null
    for(url in urls.distinct()) {
        currentCoroutineContext().ensureActive()
        try { transfer(url); return }
        catch(e: Exception) {
            currentCoroutineContext().ensureActive()
            if(e !is IOException && e !is IllegalStateException) throw e
            failure = e
        }
    }
    if(failure is MediaHttpException && failure.code in setOf(404, 410) && refreshOnUnavailable != null) {
        currentCoroutineContext().ensureActive()
        downloadWithFallback(refreshOnUnavailable(), transfer = transfer)
        return
    }
    throw checkNotNull(failure)
}
