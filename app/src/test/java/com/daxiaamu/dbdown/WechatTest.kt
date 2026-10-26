package com.daxiaamu.dbdown

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WechatTest {
    @Test fun recognizesShareTextAndCanonicalPreviewWithoutTrustingOtherHosts() {
        val link = Links.detect("微信视频号：https://weixin.qq.com/sph/AsTZuV2FYU。")!!
        assertEquals(Platform.WECHAT, link.platform)
        assertEquals("wx:AsTZuV2FYU", link.key)
        assertEquals(link, Links.detect("https://channels.weixin.qq.com/finder-preview/pages/sph?id=AsTZuV2FYU"))
        listOf("https://weixin.qq.com.evil.com/sph/AsTZuV2FYU", "https://evil.com/sph/AsTZuV2FYU",
            "https://weixin.qq.com/sph/", "https://weixin.qq.com/").forEach { assertNull(Links.detect(it)) }
    }

    @Test fun picksOriginalTrackAndPreservesUnsignedKeyAndQueryParameters() {
        val link = Links.detect("https://weixin.qq.com/sph/AsTZuV2FYU")!!
        val feed = JSONObject("""{"errCode":0,"data":{"feedInfo":{"description":"视频号测试","decodeKey":18446744073709551615,
            "originVideoUrl":"https://media.example.com/original?token=abc&encfilekey=xyz",
            "h265VideoInfo":{"videoUrl":"https://media.example.com/hevc"}}}}""")
        val info = wechatVideoInfo(link, feed, null)
        assertEquals("18446744073709551615", info.videoDecodeKey)
        assertEquals("https://media.example.com/original?token=abc&encfilekey=xyz", info.video)
        assertTrue(info.specifications!!.videos.all { it.hasAudio })
        assertEquals("https://media.example.com/hevc", wechatVideoInfo(link, feed, TrackSelection("wx:hevc")).video)
    }

    @Test fun successfulYuanbaoLoginUsesTopLevelIdentityRatherThanStatusTwoAsExpiry() {
        assertEquals(AccountStatus.VALID, accountVerdict(Platform.WECHAT, """{"status":2,"userId":"123","anonUser":false}"""))
        assertEquals(AccountStatus.UNKNOWN, accountVerdict(Platform.WECHAT, """{"status":2}"""))
        assertEquals(AccountStatus.UNKNOWN, accountVerdict(Platform.WECHAT, """{"status":2,"userId":"a_guest","anonUser":true}"""))
    }

    @Test fun cipherSupportsUnsignedSeedsShortFilesAndLeavesTailUnchanged() {
        for(key in listOf("0", "18446744073709551615", "12345678901234567890")) {
            val original = ByteArray(131085) { (it % 251).toByte() }
            val bytes = original.copyOf()
            WechatCipher.transform(bytes, key)
            assertFalse(original.take(16) == bytes.take(16))
            assertArrayEquals(original.copyOfRange(131072, original.size), bytes.copyOfRange(131072, bytes.size))
            WechatCipher.transform(bytes, key)
            assertArrayEquals(original, bytes)
            val short = byteArrayOf(1, 2, 3)
            WechatCipher.transform(short, key); WechatCipher.transform(short, key)
            assertArrayEquals(byteArrayOf(1, 2, 3), short)
        }
    }

    @Test fun matchesIndependentIsaac64ReferenceVectors() {
        val vectors = mapOf(
            "0" to "3b987b16a759e3ddc755060db384eca9a24063cd0e31aebb11683366030f0111",
            "18446744073709551615" to "6eb7a7ff8a1f2be8cfeea9aba718d1823004b5d18ebb76bea0978a26e43bd6f5",
            "12345678901234567890" to "f43127177680ff9c9c4f35ff4efed400556af145f2428d2ed2e33960554dffcd"
        )
        vectors.forEach { (key, expected) ->
            val bytes = ByteArray(131080)
            WechatCipher.transform(bytes, key)
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, hash)
        }
    }
}
