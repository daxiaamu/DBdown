package com.daxiaamu.dbdown

import org.junit.Assert.assertEquals
import org.junit.Test

class DouyinShareTextTest {
    private val url = "https://v.douyin.com/7AmdOeBRQRM/"
    private val prefix = "1.53 复制打开抖音，看看【高冷汉堡包🍔的作品】树是三元，包是三元，那话筒是多少元？ # 安阳殷商... "
    private val suffix = " 10/09 F@u.fO lpQ:/ :3pm   "

    private fun assertVideo(text: String) {
        val link = Links.detect(text)
        assertEquals(Platform.DOUYIN, link?.platform)
        assertEquals(url, link?.url)
        assertEquals("douyin-short:/7AmdOeBRQRM", link?.key)
    }

    @Test fun extractsVideoFromExactDouyinClipboardText() {
        assertVideo(prefix + url + suffix)
    }

    @Test fun extractsVideoWhenChatFormatsTheUrlAsAMarkdownLink() {
        assertVideo(prefix + "[" + url + "](" + url + ")" + suffix)
    }
}
