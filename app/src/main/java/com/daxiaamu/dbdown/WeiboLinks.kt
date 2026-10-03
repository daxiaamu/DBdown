package com.daxiaamu.dbdown

import java.net.URI
import java.net.URLDecoder

internal object WeiboLinks {
    private val postId=Regex("[A-Za-z0-9]{8,24}")
    private val objectId=Regex("[0-9]{2,8}:(?:[0-9a-fA-F]{32}|[0-9]{16,22})")
    fun fromUri(uri: URI): VideoLink? {
        val host=uri.host?.lowercase() ?: return null
        val path=uri.path.orEmpty().trimEnd('/')
        if(host=="t.cn" && Regex("/[A-Za-z0-9]{5,16}").matches(path))
            return VideoLink(Platform.WEIBO,"https://t.cn$path","weibo-short:$path")
        if(host !in setOf("weibo.com","www.weibo.com","m.weibo.cn","weibo.cn","video.weibo.com")) return null
        val query=uri.rawQuery.orEmpty().split('&').mapNotNull {
            val pair=it.split('=',limit=2)
            if(pair.size==2) URLDecoder.decode(pair[0],"UTF-8") to URLDecoder.decode(pair[1],"UTF-8") else null
        }.groupBy({it.first},{it.second})
        val fid=if(path.startsWith("/tv/show/")) path.removePrefix("/tv/show/")
            else if(path in setOf("/show","/s/video/index","/tv/show")) query["fid"]?.singleOrNull() else null
        if(fid != null) return fid.takeIf(objectId::matches)?.let {
            VideoLink(Platform.WEIBO,"https://weibo.com/tv/show/$it","wb-video:$it")
        }
        val id=when {
            host=="m.weibo.cn" -> Regex("/(?:status|detail)/([A-Za-z0-9]+)").matchEntire(path)?.groupValues?.get(1)
            host in setOf("weibo.com","www.weibo.com") ->
                Regex("/[0-9]+/([A-Za-z0-9]+)").matchEntire(path)?.groupValues?.get(1)
                ?: if(Regex("/u/[0-9]+").matches(path)) query["layerid"]?.singleOrNull() else null
            host=="weibo.cn" -> Regex("/comment/([A-Za-z0-9]+)").matchEntire(path)?.groupValues?.get(1)
            else -> null
        }
        return id?.takeIf(postId::matches)?.let { VideoLink(Platform.WEIBO,"https://m.weibo.cn/detail/$it","wb:$it") }
    }
}
