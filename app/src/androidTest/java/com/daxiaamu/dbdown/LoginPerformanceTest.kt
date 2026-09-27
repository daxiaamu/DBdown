package com.daxiaamu.dbdown

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class LoginPerformanceTest {
    @Test fun biliLayoutDoesNotOverlapAndUiRemainsResponsiveAcrossRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent = Intent(context, LoginActivity::class.java).putExtra("platform", Platform.BILI.name)
        ActivityScenario.launch<LoginActivity>(intent).use { scenario ->
            var original: LoginActivity? = null
            scenario.onActivity { original = it }
            val ready = AtomicBoolean(false)
            val deadline = System.currentTimeMillis() + 45000
            while(System.currentTimeMillis() < deadline && !ready.get()) {
                scenario.onActivity { activity ->
                    activity.browser!!.evaluateJavascript("""
                        (function(){
                            var terms=document.querySelector('.explain-tips');
                            var button=Array.from(document.querySelectorAll('.login-btn')).find(e=>e.getBoundingClientRect().height>0);
                            if(!terms||!button) return false;
                            var a=terms.getBoundingClientRect(),b=button.getBoundingClientRect();
                            return a.height>0 && a.top>=b.bottom && getComputedStyle(terms).position==='static';
                        })()
                    """.trimIndent()) { ready.set(it == "true") }
                }
                Thread.sleep(150)
            }
            assertTrue("Official Bili agreement must not cover the login button", ready.get())
            val samples = mutableListOf<Long>()
            repeat(30) {
                val latch = CountDownLatch(1)
                val start = System.nanoTime()
                Handler(Looper.getMainLooper()).post { samples += (System.nanoTime()-start)/1_000_000; latch.countDown() }
                assertTrue("UI thread must keep responding", latch.await(1, TimeUnit.SECONDS))
                Thread.sleep(100)
            }
            val p95 = samples.sorted()[28]
            android.util.Log.i("DBDownPerf", "Bili main-thread post latency: p95=" + p95 + "ms max=" + samples.max() + "ms")
            assertTrue("Main thread stalls while displaying login: " + p95 + "ms", p95 < 250)
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            java.io.File(context.getExternalFilesDir(null), "bili-layout-fixed.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
            scenario.recreate()
            scenario.onActivity {
                assertNotSame(original, it)
                assertNull("Previous WebView owner must release its browser", original!!.browser)
                assertNotNull(it.browser)
            }
        }
    }

    @Test fun applicationIdentityAndLocalizedNamesAreCorrect() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.daxiaamu.dbdown", context.packageName)
        for((language, name) in listOf("zh" to "逗逼下载器", "en" to "DBDown")) {
            val config = android.content.res.Configuration(context.resources.configuration)
            config.setLocale(java.util.Locale.forLanguageTag(language))
            assertEquals(name, context.createConfigurationContext(config).getString(R.string.app_name))
        }
    }
}
