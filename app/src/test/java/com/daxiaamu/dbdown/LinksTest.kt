package com.daxiaamu.dbdown

import org.junit.Assert.*
import org.junit.Test

class LinksTest {
    private val bv = "BV1xx411c7mD"
    @Test fun extractsBiliFromShareText() {
        val link = Links.detect("【分享一个视频】 https://www.bilibili.com/video/$bv/?spm_id_from=333.999&p=3。")
        assertEquals(Platform.BILI, link?.platform)
        assertEquals(3, link?.part)
        assertEquals("$bv:p3", link?.key)
    }
    @Test fun acceptsBareBvAndAv() {
        assertEquals("$bv:p1", Links.detect(bv)?.key)
        assertEquals("av170001:p1", Links.detect("av170001")?.key)
    }
    @Test fun stripsTrackingForDeduplication() {
        val first = Links.detect("https://www.bilibili.com/video/$bv?share_source=copy")
        val second = Links.detect("https://m.bilibili.com/video/$bv?p=1&spm_id_from=123")
        assertEquals(first?.key, second?.key)
        assertEquals(Links.detect(bv)?.key, first?.key)
    }
    @Test fun acceptsDouyinShareTextWithChinesePunctuation() {
        val link = Links.detect("3.28 复制打开抖音，看看这个作品 https://v.douyin.com/Abc123_/ 09/27")
        assertEquals(Platform.DOUYIN, link?.platform)
        assertEquals("https://v.douyin.com/Abc123_/", link?.url)
    }
    @Test fun acceptsDouyinVideoAndMobileShare() {
        val desktop = Links.detect("https://www.douyin.com/video/7421234567890123456?from=copy")
        val mobile = Links.detect("https://www.iesdouyin.com/share/video/7421234567890123456/")
        assertEquals(desktop?.key, mobile?.key)
        assertEquals("dy:7421234567890123456", mobile?.key)
    }
    @Test fun recognizesFeaturedModalLinkAndDeduplicatesWithCanonicalWork() {
        val id = "7678889454471351592"
        val link = Links.detect("https://www.douyin.com/jingxuan?modal_id=$id")!!
        assertEquals(Platform.DOUYIN, link.platform)
        assertEquals("https://www.douyin.com/video/$id", link.url)
        assertEquals(Links.detect("https://www.douyin.com/video/$id")!!.key, link.key)
        assertEquals(Links.detect("https://www.douyin.com/note/$id")!!.key, link.key)
    }
    @Test fun recognizesModalLinksInShareTextWithTrackingAndEncoding() {
        val id = "7678889454471351592"
        listOf(
            "复制打开抖音 [作品](https://www.douyin.com/jingxuan/?from=copy&modal_id=$id&foo=bar)",
            "https://www.douyin.com/?modal_id=$id",
            "www.douyin.com/jingxuan?modal_id=%37${id.drop(1)}#detail"
        ).forEach { assertEquals(it, "dy:$id", Links.detect(it)?.key) }
    }
    @Test fun rejectsInvalidOrAmbiguousModalLinks() {
        val prefix = "https://www.douyin.com/jingxuan?"
        listOf("", "modal_id=", "modal_id=12", "modal_id=-7678889454471351592",
            "modal_id=7678889454471351592x", "modal_id=0", "modal_id=7678889454471351592&modal_id=7678889454471351593",
            "redirect=modal_id=7678889454471351592", "foo=bar#modal_id=7678889454471351592"
        ).forEach { assertNull(it, Links.detect(prefix + it)) }
        assertNull(Links.detect("https://www.douyin.com.evil.com/jingxuan?modal_id=7678889454471351592"))
        assertNull(Links.detect("https://live.douyin.com/jingxuan?modal_id=7678889454471351592"))
        assertNull(Links.detect("https://www.douyin.com/jingxuan"))
    }
    @Test fun acceptsB23ShortUrl() {
        assertEquals("https://b23.tv/AbC9", Links.detect("https://b23.tv/AbC9/")?.url)
    }
    @Test fun rejectsUnsupportedContent() {
        listOf(
            "普通文字 123456", "https://www.bilibili.com", "https://space.bilibili.com/1234",
            "https://live.bilibili.com/1234", "https://www.bilibili.com/read/cv1234",
            "https://www.douyin.com/user/MS4wLjAB", "https://live.douyin.com/1234",
            "https://www.douyin.com/search/1234", "https://www.douyin.com/video/12",
            "https://www.bilibili.com/bangumi/play/ep1234", ""
        ).forEach { assertNull(it, Links.detect(it)) }
    }
    @Test fun rejectsLookalikeDomainsAndCredentials() {
        listOf(
            "https://evilbilibili.com/video/$bv",
            "https://www.bilibili.com.evil.com/video/$bv",
            "https://www.douyin.com.evil.com/video/7421234567890123456",
            "https://evil.com/$bv"
        ).forEach { assertNull(it, Links.detect(it)) }
        assertNull(Links.fromUrl("https://evil@www.bilibili.com/video/$bv"))
        assertNull(Links.fromUrl("https://www.bilibili.com:8080/video/$bv"))
    }
    @Test fun rejectsInvalidBv() {
        assertNull(Links.detect("BV0000000000"))
        assertNull(Links.detect("BV1xx411c7mDzz"))
    }
    @Test fun ignoresUnrelatedLinkAndFindsVideo() {
        assertEquals(Platform.DOUYIN, Links.detect("https://example.com/x https://v.douyin.com/Abc123/")?.platform)
    }
    @Test fun preservesPartForDuplicateDetection() {
        assertNotEquals(Links.detect("https://www.bilibili.com/video/$bv?p=1")?.key,
            Links.detect("https://www.bilibili.com/video/$bv?p=2")?.key)
    }
    @Test fun handlesMissingProtocol() {
        assertEquals(Platform.BILI, Links.detect("www.bilibili.com/video/$bv")?.platform)
    }
    @Test fun promptDismissalSuppressesRepeatedForegrounds() {
        val gate = PromptGate()
        assertTrue(gate.shouldShow("dy:123"))
        gate.handled("dy:123")
        repeat(5) { assertFalse(gate.shouldShow("dy:123")) }
        assertTrue(gate.shouldShow("dy:456"))
    }
    @Test fun promptHistorySurvivesRecreationAndIsBounded() {
        val gate = PromptGate()
        repeat(110) { gate.handled("video:$it") }
        assertEquals(100, gate.keys().size)
        val restored = PromptGate(gate.keys().toMutableSet())
        assertFalse(restored.shouldShow("video:109"))
        assertTrue(restored.shouldShow("video:0"))
    }
}
