package com.daxiaamu.dbdown

import java.net.URI

internal object WechatLinks {
    fun fromUri(uri: URI): VideoLink? {
        val id = when(uri.host?.lowercase()) {
            "weixin.qq.com" -> Regex("^/sph/([A-Za-z0-9_-]{4,128})/?$").matchEntire(uri.path.orEmpty())?.groupValues?.get(1)
            "channels.weixin.qq.com" -> if(uri.path == "/finder-preview/pages/sph")
                uri.rawQuery.orEmpty().split('&').filter { it.substringBefore('=') == "id" }
                    .singleOrNull()?.substringAfter('=')?.takeIf { Regex("[A-Za-z0-9_-]{4,128}").matches(it) } else null
            else -> null
        } ?: return null
        return VideoLink(Platform.WECHAT, "https://weixin.qq.com/sph/$id", "wx:$id")
    }
}
