package com.daxiaamu.dbdown

import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Additional official client contexts are independent probes, never a replacement for the signed-in page. */
internal object YoutubeClients {
    data class Profile(val id: Int, val client: JSONObject) {
        val userAgent get() = client.getString("userAgent")
        val name get() = client.getString("clientName")
    }
    fun profiles() = listOf(
        Profile(101,JSONObject().put("clientName","VISIONOS").put("clientVersion","1.02")
            .put("deviceMake","Apple").put("deviceModel","RealityDevice17,1").put("osName","visionOS").put("osVersion","26.5.23O471")
            .put("userAgent","Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15")),
        Profile(5,JSONObject().put("clientName","IOS").put("clientVersion","21.26.4")
            .put("deviceMake","Apple").put("deviceModel","iPhone16,2").put("osName","iPhone").put("osVersion","18.3.2.22D82")
            .put("userAgent","com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)")),
        Profile(1,JSONObject().put("clientName","WEB").put("clientVersion","2.20260708.00.00")
            .put("userAgent","Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.5 Safari/605.1.15"))
    )
    fun fetch(profile: Profile, link: VideoLink, network: OkHttpClient, track: (Call)->Unit): JSONObject {
        val client = network.newBuilder().cookieJar(CookieJar.NO_COOKIES).callTimeout(12,TimeUnit.SECONDS).build()
        val body = JSONObject().put("videoId",link.key.removePrefix("yt:"))
            .put("context",JSONObject().put("client",JSONObject(profile.client.toString()).put("hl","en").put("gl","US")))
        val request=Request.Builder().url("https://youtubei.googleapis.com/youtubei/v1/player?prettyPrint=false")
            .header("User-Agent",profile.userAgent).header("X-YouTube-Client-Name",profile.id.toString())
            .header("X-YouTube-Client-Version",profile.client.getString("clientVersion"))
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return client.newCall(request).also(track).execute().use { response ->
            check(response.isSuccessful)
            val raw=response.body!!.byteStream().readNBytes(4*1024*1024+1)
            check(raw.size<=4*1024*1024)
            JSONObject(raw.toString(Charsets.UTF_8)).put("_dbdownUa",profile.userAgent).put("_dbdownSource",profile.name)
        }
    }
}
