package com.daxiaamu.dbdown

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DouyinCombinedQualityTest {
    private val link = Links.detect("https://www.douyin.com/video/7690882615418703138")!!
    private fun mobile(width: Int = 720, height: Int = 1280): String {
        val video = JSONObject().put("width", 9999).put("height", 9999)
            .put("play_addr", JSONObject().put("url_list", JSONArray().put("https://cdn.example/default.mp4")))
            .put("bit_rate", JSONArray().put(JSONObject().put("bit_rate", 803121)
                .put("play_addr", JSONObject().put("width", width).put("height", height)
                    .put("url_list", JSONArray().put("https://cdn.example/mobile.mp4")))))
        val item = JSONObject().put("aweme_id", "7690882615418703138").put("video", video)
        val data = JSONObject().put("loaderData", JSONObject().put("video_(id)/page", JSONObject()
            .put("videoInfoRes", JSONObject().put("item_list", JSONArray().put(item)))))
        return "<script>window._ROUTER_DATA=$data</script>"
    }
    private fun desktop() = JSONObject().put("awemeId", "7690882615418703138").put("video", JSONObject()
        .put("bitRateList", JSONArray().put(JSONObject().put("width", 1080).put("height", 1920)
            .put("bitRate", 340098).put("isH265", 1).put("format", "mp4")
            .put("playAddr", JSONArray().put(JSONObject().put("src", "https://cdn.example/desktop.mp4"))))))

    @Test fun usable720ShareIsUpgradedTo1080HevcDespiteLowerBitrate() {
        val page = mobile()
        val result = DouyinPage.supplementVideo(page, desktop().toString(), DouyinPage.parse(page, link))
        assertEquals("https://cdn.example/desktop.mp4", result.video)
        assertEquals("https://cdn.example/mobile.mp4", result.videoFallbacks.first())
        assertTrue(result.videoFallbacks.contains("https://cdn.example/default.mp4"))
        assertEquals("", result.resolution) // Remains measured from downloaded bytes.
        assertNull(result.audio) // Preserve original muxed audio.
    }
    @Test fun desktopDoesNotDowngradeHigherMobileVariant() {
        val page = mobile(2160, 3840)
        val result = DouyinPage.supplementVideo(page, desktop().toString(), DouyinPage.parse(page, link))
        assertEquals("https://cdn.example/mobile.mp4", result.video)
        assertEquals("https://cdn.example/desktop.mp4", result.videoFallbacks.first())
    }
    @Test fun emptyDesktopPreservesShareFallbackOrder() {
        val page = mobile()
        val info = DouyinPage.parse(page, link)
        val web = desktop().put("video", JSONObject())
        assertEquals(info, DouyinPage.supplementVideo(page, web.toString(), info))
    }
    @Test fun rejectsMismatchedWorkAndDeduplicatesCandidates() {
        val page = mobile()
        val info = DouyinPage.parse(page, link)
        assertTrue(runCatching { DouyinPage.supplementVideo(page,
            desktop().put("awemeId", "1234567890").toString(), info) }.isFailure)
        val web = desktop()
        web.getJSONObject("video").getJSONArray("bitRateList").getJSONObject(0)
            .put("playAddr", JSONArray().put(JSONObject().put("src", info.video)))
        val result = DouyinPage.supplementVideo(page, web.toString(), info)
        assertEquals(1, (listOf(result.video) + result.videoFallbacks).count { it == info.video })
    }
}
