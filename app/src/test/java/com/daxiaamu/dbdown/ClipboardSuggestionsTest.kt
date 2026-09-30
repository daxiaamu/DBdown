package com.daxiaamu.dbdown
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ClipboardSuggestionsTest {
    private val link=Links.detect("https://www.bilibili.com/video/BV1xx411c7mD?p=1")!!
    private fun info()=VideoInfo(link,link.key,"Title","",referer="",userAgent="")
    @Test fun promptIsImmediateAndResolutionUpdatesIt()=runTest {
        val result=CompletableDeferred<VideoInfo>()
        val prompts=ClipboardSuggestions(backgroundScope,{result.await()},{true},{})
        prompts.inspect(link.url,100)
        assertEquals(ClipboardPrompt.Resolving(link,100),prompts.state)
        runCurrent(); result.complete(info()); runCurrent()
        assertEquals(ClipboardPrompt.Ready(link,info(),100),prompts.state)
    }
    @Test fun ordinaryTextDoesNotResolve()=runTest {
        val prompts=ClipboardSuggestions(backgroundScope,{error("unexpected resolution")},{true},{})
        listOf(null,"ordinary text","https://www.douyin.com/user/12345678").forEach { prompts.inspect(it); assertNull(prompts.state) }
    }
    @Test fun ignoredRequestCannotReappear()=runTest {
        val result=CompletableDeferred<VideoInfo>()
        val handled=mutableSetOf<String>()
        val prompts=ClipboardSuggestions(backgroundScope,{withContext(NonCancellable){result.await()}},{it !in handled},handled::add)
        prompts.inspect(link.url,100); runCurrent(); prompts.dismiss()
        result.complete(info()); runCurrent(); assertNull(prompts.state)
        prompts.inspect(link.url,100); assertNull(prompts.state)
    }
    @Test fun newTimestampPromptsAgainAcrossRecreation()=runTest {
        val handled=mutableSetOf<String>()
        fun create()=ClipboardSuggestions(backgroundScope,{info()},{it !in handled},handled::add)
        val first=create(); first.inspect(link.url,100); runCurrent(); first.dismiss()
        val next=create(); next.inspect(link.url,100); assertNull(next.state)
        next.inspect(link.url,101); assertEquals(ClipboardPrompt.Resolving(link,101),next.state)
        runCurrent(); assertEquals(101L,next.state!!.timestamp)
    }
    @Test fun failedResolutionCanBeRetried()=runTest {
        var calls=0
        val prompts=ClipboardSuggestions(backgroundScope,{if(calls++==0) error("network") else info()},{true},{})
        prompts.inspect(link.url); runCurrent(); assertTrue(prompts.state is ClipboardPrompt.Failed)
        prompts.retry(); runCurrent(); assertTrue(prompts.state is ClipboardPrompt.Ready)
    }
}
