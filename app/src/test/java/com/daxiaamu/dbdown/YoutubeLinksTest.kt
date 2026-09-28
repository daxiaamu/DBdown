package com.daxiaamu.dbdown
import org.junit.Assert.*
import org.junit.Test

class YoutubeLinksTest {
    @Test fun suppliedSamples() {
        assertEquals("yt:qIzGvexMjpA", Links.detect("https://www.youtube.com/watch?v=qIzGvexMjpA")?.key)
        assertEquals("yt:-9OM3w3TWUs", Links.detect("https://www.youtube.com/shorts/-9OM3w3TWUs  ")?.key)
        assertEquals("yt:BLKegH19KGI", Links.detect("BLKegH19KGI")?.key)
    }
    @Test fun canonicalizesVariantsAndShareText() {
        val expected = Links.detect("qIzGvexMjpA")
        listOf("https://youtu.be/qIzGvexMjpA?si=abc", "https://m.youtube.com/watch?v=qIzGvexMjpA&t=12",
            "https://www.youtube.com/shorts/qIzGvexMjpA", "https://youtube.com/embed/qIzGvexMjpA",
            "https://youtube.com/live/qIzGvexMjpA").forEach {
            assertEquals(expected, Links.detect("看看这个视频：$it 。"))
        }
    }
    @Test fun rejectsNonVideosAndLookalikeHosts() {
        listOf("https://youtube.com/playlist?list=abc", "https://youtube.com/@someone",
            "https://youtube.com/watch?v=short", "https://youtube.com/watch?v=qIzGvexMjpA&v=BLKegH19KGI",
            "https://youtube.com.evil.com/watch?v=qIzGvexMjpA", "https://evil.com/qIzGvexMjpA",
            "https://youtu.be/qIzGvexMjpA/extra", "prefix qIzGvexMjpA suffix").forEach { assertNull(it,Links.detect(it)) }
    }
}
