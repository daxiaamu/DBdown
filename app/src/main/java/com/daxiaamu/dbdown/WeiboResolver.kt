package com.daxiaamu.dbdown

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Official post/player endpoints, with an isolated visitor session and existing WebView login cookies. */
internal class WeiboResolver(private val trackCall: (Call)->Unit, private val ensureActive: ()->Unit = {}) {
    private val guests=SessionCookies()
    private val client=OkHttpClient.Builder().cookieJar(object: CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val signed=WebAccounts.cookies(url)
            return signed + guests.loadForRequest(url).filter { guest -> signed.none { it.name==guest.name } }
        }
        override fun saveFromResponse(url: HttpUrl,cookies: List<Cookie>) { guests.saveFromResponse(url,cookies) }
    }).connectTimeout(12,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).callTimeout(25,TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val url=chain.request().url
            check(url.isHttps && officialHost(url.host)) { "微博链接跳转到了不支持的网站" }
            chain.proceed(chain.request())
        }.build()
    private var visitorReady=false
    private fun officialHost(host: String)=host=="t.cn" || listOf("weibo.com","weibo.cn").any { host==it || host.endsWith(".$it") }
    private fun request(url: String, form: FormBody? = null, redirects: Boolean = true): Pair<String,HttpUrl> {
        ensureActive()
        val builder=Request.Builder().url(url).header("User-Agent",VideoResolver.DESKTOP).header("Referer","https://weibo.com/")
        if(form!=null) builder.post(form)
        val network=if(redirects) client else client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        return network.newCall(builder.build()).also(trackCall).execute().use {
            check(it.isSuccessful || (!redirects && it.isRedirect)) { "微博服务器返回 ${it.code}，请稍后重试" }
            val bytes=it.body!!.byteStream().readNBytes(6*1024*1024+1)
            check(bytes.size<=6*1024*1024) { "微博返回内容过大" }
            bytes.toString(Charsets.UTF_8) to (if(!redirects && it.isRedirect) it.request.url.resolve(it.header("Location").orEmpty()) ?: error("微博短链跳转无效") else it.request.url)
        }
    }
    private fun json(url: String, form: FormBody?=null): JSONObject {
        var (body,location)=request(url,form)
        if(!body.trimStart().startsWith("{") && (location.host.contains("passport.weibo") || body.contains("Sina Visitor System")) && !visitorReady) {
            visitorReady=true
            val fp=JSONObject().put("os","1").put("browser","Chrome130,0,0,0").put("fonts","undefined").put("screenInfo","1920*1080*24").put("plugins","")
            val raw=request("https://passport.weibo.com/visitor/genvisitor",FormBody.Builder().add("cb","gen_callback").add("fp",fp.toString()).build()).first
            val data=JSONObject(raw.substring(raw.indexOf('{'),raw.lastIndexOf('}')+1)).getJSONObject("data")
            val visitor="https://passport.weibo.com/visitor/visitor".toHttpUrl().newBuilder()
                .addQueryParameter("a","incarnate").addQueryParameter("t",data.getString("tid"))
                .addQueryParameter("w",if(data.optBoolean("new_tid")) "3" else "2")
                .addQueryParameter("c",data.optInt("confidence",100).toString().padStart(3,'0'))
                .addQueryParameter("gc","").addQueryParameter("cb","cross_domain").addQueryParameter("from","weibo").build()
            request(visitor.toString())
            body=request(url,form).first
        }
        check(body.trimStart().startsWith("{")) { "微博要求登录或网页验证，请在设置中登录微博后重试" }
        val result=JSONObject(body)
        check(!result.has("ok") || result.optInt("ok")!=0) { "微博内容不可访问，请确认作品仍存在并在设置中登录后重试" }
        return result
    }
    private fun expand(link: VideoLink): VideoLink {
        if(!link.key.startsWith("weibo-short:")) return link
        var url=link.url
        repeat(6) {
            val (body,next)=request(url,redirects=false)
            var target=next
            if(next.toString()==url) {
                val href=Regex("""<a\b[^>]*\bhref=["']([^"']+)["']""",RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)?.replace("&amp;","&")
                target=href?.let(next::resolve) ?: error("短链接未指向微博作品")
            }
            if(!target.isHttps) target=target.newBuilder().scheme("https").build()
            check(officialHost(target.host) && target.username.isEmpty() && target.password.isEmpty()) { "短链接未指向微博作品" }
            url=target.toString()
            Links.fromUrl(url)?.takeIf { it.platform==Platform.WEIBO && !it.key.startsWith("weibo-short:") }?.let { return it }
        }
        error("微博短链接跳转次数过多")
    }
    fun resolve(original: VideoLink, selection: TrackSelection?): VideoInfo {
        var link=expand(original)
        var objectId: String?=null
        if(link.key.startsWith("wb-video:")) {
            objectId=link.key.removePrefix("wb-video:")
            val url="https://weibo.com/tv/api/component".toHttpUrl().newBuilder().addQueryParameter("page","/tv/show/$objectId").build()
            val body=JSONObject().put("Component_Play_Playinfo",JSONObject().put("oid",objectId)).toString()
            val item=json(url.toString(),FormBody.Builder().add("data",body).build()).getJSONObject("data").getJSONObject("Component_Play_Playinfo")
            val mid=item.optString("mid")
            link=Links.fromUrl("https://m.weibo.cn/detail/$mid") ?: error("未找到微博视频对应作品")
        }
        val id=link.key.removePrefix("wb:")
        val post=try { json("https://weibo.com/ajax/statuses/show".toHttpUrl().newBuilder().addQueryParameter("id",id).build().toString()) }
        catch(e: Exception) {
            ensureActive()
            if(e is kotlinx.coroutines.CancellationException) throw e
            try { json("https://m.weibo.cn/statuses/show".toHttpUrl().newBuilder().addQueryParameter("id",id).build().toString()).getJSONObject("data") }
            catch(second: Exception) { ensureActive(); if(second is kotlinx.coroutines.CancellationException) throw second; throw e }
        }
        return WeiboMedia.parse(post,link,selection,objectId)
    }
}
