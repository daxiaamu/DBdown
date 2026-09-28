package com.daxiaamu.dbdown

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.Request
import android.webkit.CookieManager
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Read-only manual diagnostic. Never prints cookies, account IDs or response bodies. */
@RunWith(AndroidJUnit4::class)
class DouyinAccountProbeTest {
    @Test fun officialAccountEndpoints() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("douyinAccountProbe") == "true")
        val client = OkHttpClient.Builder().callTimeout(15,TimeUnit.SECONDS).build()
        for(aid in listOf("authenticated", "anonymous")) {
            val url = "https://www.douyin.com/aweme/v1/web/user/profile/self/?aid=6383&device_platform=webapp"
            val summary=runCatching {
                client.newCall(Request.Builder().url(url).header("Cookie",if(aid == "authenticated") CookieManager.getInstance().getCookie(url).orEmpty() else "")
                    .header("User-Agent",VideoResolver.DESKTOP).header("Referer","https://www.douyin.com/").build()).execute().use {
                    val body=it.body?.string().orEmpty()
                    val json=runCatching { JSONObject(body) }.getOrNull()
                    val data=json?.optJSONObject("data")
                    "mode=$aid HTTP=${it.code} status=${json?.optInt("status_code",-1)} " +
                        "hasIdentity=${json?.optJSONObject("user")?.optString("uid").let { id -> !id.isNullOrBlank() && id != "0" && id != "null" }} " +
                        "verdict=${accountVerdict(Platform.DOUYIN,body)}"

                }
            }.getOrElse { "aid=$aid exception=${it.javaClass.simpleName}" }
            InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply { putString("stream",summary+"\n") })
        }
    }
}
