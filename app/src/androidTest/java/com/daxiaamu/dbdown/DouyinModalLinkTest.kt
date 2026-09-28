package com.daxiaamu.dbdown

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DouyinModalLinkTest {
    @Test fun featuredLinkResolvesTheRequestedWorkOnDevice() = runBlocking {
        val link = Links.detect("https://www.douyin.com/jingxuan?modal_id=7678889454471351592")!!
        val info = VideoResolver().resolve(link)
        assertEquals("dy:7678889454471351592", info.id)
        assertTrue(info.title.isNotBlank())
        assertTrue(info.video.startsWith("https://") || info.images.isNotEmpty())
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putString("stream", "Resolved requested work: ${info.id}; images=${info.images.size}; video=${info.video.isNotEmpty()}\n")
        })
    }
}
