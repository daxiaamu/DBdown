package com.daxiaamu.dbdown

import org.junit.Assert.assertEquals
import org.junit.Test

class BiliShareTextTest {
    private val url = "https://b23.tv/2vBN1dt"
    private val title = "【见你所见，还原真实！OPPO Find X10 Pro Max 体验-哔哩哔哩】 "

    private fun assertVideo(text: String) {
        val link = Links.detect(text)
        assertEquals(Platform.BILI, link?.platform)
        assertEquals(url, link?.url)
        assertEquals("b23.tv/2vBN1dt", link?.key)
    }

    @Test fun extractsShortLinkFromExactBiliAppShareText() {
        assertVideo(title + url)
    }

    @Test fun acceptsTheSameShortLinkByItself() {
        assertVideo(url)
    }

    @Test fun acceptsShareTextWithMarkdownLink() {
        assertVideo(title + "[" + url + "](" + url + ")")
    }
}
