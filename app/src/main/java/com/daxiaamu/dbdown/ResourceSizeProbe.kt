package com.daxiaamu.dbdown

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume

/** Bounded, cancellable HEAD probes run alongside downloads, never delaying their start. */
internal suspend fun probeResourceSize(client: OkHttpClient, resource: DownloadResource, referer: String, track: (Call)->Unit): Long =
    suspendCancellableCoroutine { continuation ->
        val call=client.newCall(Request.Builder().url(resource.url).head().header("User-Agent",resource.userAgent)
            .header("Referer",referer).header("Accept-Encoding","identity").build())
        continuation.invokeOnCancellation { call.cancel() }
        track(call)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { continuation.resume(-1L) }
            override fun onResponse(call: Call, response: Response) {
                val size=response.use {
                    if(it.code==200 && it.header("Content-Encoding").let { value -> value==null || value.equals("identity",true) })
                        it.header("Content-Length")?.toLongOrNull()?.takeIf { value -> value>=0 } ?: -1L
                    else -1L
                }
                continuation.resume(size)
            }
        })
    }
