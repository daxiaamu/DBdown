package com.daxiaamu.dbdown

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import java.net.URLDecoder

internal fun decodeYoutubeUrl(id: String, raw: String): String {
    val direct = if(raw.startsWith("https://")) raw else {
        val parts = raw.split('&').associate { part ->
            val pair = part.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        }
        val url = parts["url"]?.toHttpUrlOrNull() ?: error("YouTube 视频签名格式无效")
        url.newBuilder().addQueryParameter(parts["sp"] ?: "signature",
            YoutubeJavaScriptPlayerManager.deobfuscateSignature(id, parts["s"].orEmpty())).build().toString()
    }
    return YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(id, direct)
}

/** Manifests use /n/value in addition to the usual query parameter. */
internal fun decodeYoutubeManifestUrl(id: String, raw: String,
    decode: (String,String) -> String = ::decodeYoutubeUrl): String {
    val url = raw.toHttpUrlOrNull() ?: error("Invalid manifest URL")
    val index = url.pathSegments.indexOf("n")
    val builder = url.newBuilder()
    if(index >= 0 && index + 1 < url.pathSegments.size) {
        val value = url.pathSegments[index + 1]
        val probe = url.newBuilder().encodedPath("/").query(null).addQueryParameter("n",value).build()
        val decoded = decode(id,probe.toString()).toHttpUrlOrNull()?.queryParameter("n") ?: error("Invalid n parameter")
        builder.setPathSegment(index + 1,decoded)
    }
    val transformed = builder.build().toString()
    return if(url.queryParameter("n") != null) decode(id,transformed) else transformed
}
