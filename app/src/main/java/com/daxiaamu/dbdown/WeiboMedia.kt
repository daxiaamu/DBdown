package com.daxiaamu.dbdown

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** Decode only the requested post's media. A linked preview or a repost is not the requested video. */
internal object WeiboMedia {
    internal fun mediaUrl(raw: String): String? {
        val normalized=if(raw.startsWith("//")) "https:$raw" else raw.replaceFirst(Regex("^http://"),"https://")
        val url=normalized.toHttpUrlOrNull() ?: return null
        return url.takeIf { it.isHttps && it.port==443 && it.username.isEmpty() && it.password.isEmpty() &&
            listOf("sinaimg.cn","weibocdn.com","weibo.com","weibo.cn").any { host -> it.host==host || it.host.endsWith(".$host") }
        }?.toString()
    }
    private fun videoUrl(raw: String)=mediaUrl(raw)?.takeIf { it.toHttpUrlOrNull()!!.encodedPath.endsWith(".mp4",true) }
    fun parse(post: JSONObject,link: VideoLink,selection: TrackSelection?=null,objectId: String?=null): VideoInfo {
        val mid=post.optString("idstr").ifBlank { post.optString("mid") }.ifBlank { post.optString("id") }
        check(mid.matches(Regex("[0-9]{8,24}"))) { "微博没有返回有效作品" }
        val canonical=VideoLink(Platform.WEIBO,"https://m.weibo.cn/detail/$mid","wb:$mid")
        val title=post.optString("text_raw").ifBlank { weiboPlainText(post.optString("text")) }.take(160).ifBlank { "微博 $mid" }
        val videos=mutableListOf<Pair<String,JSONObject>>()
        post.optJSONObject("page_info")?.let { page -> page.optJSONObject("media_info")?.let { videos += page.optString("object_id") to it } }
        val mix=post.optJSONObject("mix_media_info")?.optJSONArray("items")
        for(i in 0 until (mix?.length() ?: 0)) {
            val item=mix!!.optJSONObject(i) ?: continue
            if(item.optString("type")!="video") continue
            val data=item.optJSONObject("data") ?: continue
            data.optJSONObject("media_info")?.let { videos += data.optString("object_id") to it }
        }
        val media=videos.distinctBy { it.first.ifBlank { it.second.toString() } }.let { all ->
            if(objectId!=null) all.filter { it.first==objectId } else all
        }
        check(media.size<=1) { "这条微博包含多个视频，请分享其中一个视频的链接后下载" }
        val tracks=media.singleOrNull()?.second?.let(::tracks).orEmpty()
        if(tracks.isNotEmpty()) {
            check(selection?.audio==null) { "微博视频自带音轨，无需选择额外音轨" }
            val chosen=if(selection==null) tracks.first() else tracks.find { it.id==selection.video } ?: error("所选微博画质已不可用，请重新选择")
            val specs=MediaSpecifications(tracks.map { track ->
                TrackOption(track.id,if(track.width>0 && track.height>0) "${track.width} × ${track.height}" else "原始画质",
                    listOf(fpsLabel(track.fps),codecLabel(track.codec),bitrateLabel(track.bitrate),"自带音轨").filter(String::isNotBlank).joinToString(" · "),true)
            },emptyList(),TrackSelection(chosen.id))
            return VideoInfo(canonical,canonical.key,title,chosen.urls.first(),quality=if(chosen.height>0) "${minOf(chosen.width,chosen.height)}P" else "原始画质",
                referer="https://weibo.com/",userAgent=VideoResolver.DESKTOP,videoFallbacks=chosen.urls.drop(1),
                resolution=if(chosen.width>0 && chosen.height>0) "${chosen.width} × ${chosen.height}" else "",fps=chosen.fps,specifications=specs)
        }
        check(media.isEmpty()) { "这条微博暂未提供可下载视频，请登录后重试" }
        val images=mutableListOf<String>()
        val ids=post.optJSONArray("pic_ids")
        val infos=post.optJSONObject("pic_infos")
        fun add(pic: JSONObject) {
            val url=listOf("original","largest","large").firstNotNullOfOrNull { size -> pic.optJSONObject(size)?.optString("url")?.let(::mediaUrl) }
                ?: mediaUrl(pic.optString("url"))
            if(url!=null) images+=url
        }
        for(i in 0 until (ids?.length() ?: 0)) infos?.optJSONObject(ids!!.optString(i))?.let(::add)
        if(images.isEmpty()) post.optJSONArray("pics")?.let { pics -> for(i in 0 until pics.length()) pics.optJSONObject(i)?.let(::add) }
        check(images.isNotEmpty()) { "这条微博没有可下载的视频或图片" }
        check(selection==null) { "微博图片不支持切换视频规格" }
        return VideoInfo(canonical,canonical.key,title,"",quality="${images.size} 张图片",referer="https://weibo.com/",userAgent=VideoResolver.DESKTOP,images=images)
    }
    private fun tracks(media: JSONObject): List<DirectVideoTrack> {
        val result=mutableListOf<DirectVideoTrack>()
        val list=media.optJSONArray("playback_list")
        for(i in 0 until (list?.length() ?: 0)) {
            val play=list!!.optJSONObject(i)?.optJSONObject("play_info") ?: continue
            val url=videoUrl(play.optString("url")) ?: continue
            if(play.optString("audio_codecs")=="none") continue
            val label=play.optString("label").ifBlank { "track$i" }
            val width=play.optInt("width"); val height=play.optInt("height")
            result+=DirectVideoTrack("wb:$label:$width:$height:${play.optString("video_codecs")}",width,height,frameRate(play.optString("fps")),
                play.optString("video_codecs"),play.optLong("bitrate"),listOf(url))
        }
        if(result.isEmpty()) {
            for(field in listOf("mp4_1080p_mp4","mp4_720p_mp4","mp4_hd_url","stream_url_hd","mp4_sd_url","stream_url")) {
                val url=videoUrl(media.optString(field)) ?: continue
                val parsed=url.toHttpUrlOrNull()!!
                val dimensions=Regex("([0-9]+)x([0-9]+)").matchEntire(parsed.queryParameter("template").orEmpty())
                result+=DirectVideoTrack("wb:$field",dimensions?.groupValues?.get(1)?.toIntOrNull() ?: 0,dimensions?.groupValues?.get(2)?.toIntOrNull() ?: 0,0f,"",0,listOf(url))
            }
        }
        return result.distinctBy { it.urls.first() }.sortedWith(compareByDescending<DirectVideoTrack> { it.width.toLong()*it.height }.thenByDescending { it.fps }.thenByDescending { it.bitrate })
    }
}

internal fun weiboPlainText(html: String): String {
    val text=html.replace(Regex("<[^>]*>")," ")
    return Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);").replace(text) {
        val entity=it.groupValues[1]
        val code=when {
            entity.startsWith("#x") -> entity.drop(2).toIntOrNull(16)
            entity.startsWith("#") -> entity.drop(1).toIntOrNull()
            else -> null
        }
        if(code!=null && Character.isValidCodePoint(code)) String(Character.toChars(code))
        else when(entity) { "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "; else -> it.value }
    }.replace(Regex("\\s+")," ").trim()
}
