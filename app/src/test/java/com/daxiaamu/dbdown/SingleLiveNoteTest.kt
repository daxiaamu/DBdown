package com.daxiaamu.dbdown
import org.junit.Assert.*
import org.junit.Test
class SingleLiveNoteTest {
    private val link=Links.detect("https://www.douyin.com/note/7693082514630853989")!!
    private val page="""<script>window._ROUTER_DATA={"loaderData":{"note_(id)/page":{"videoInfoRes":{"item_list":[{"aweme_id":"7693082514630853989","desc":"single live","images":[{"uri":"photo-one","url_list":["https://p3.douyinpic.com/a.jpg"]}]}]}}}}</script>"""
    private val web="""{"awemeId":"7693082514630853989","images":[{"uri":"photo-one","clipType":5,"livePhotoType":1,"video":{"playAddr":[{"src":"https://v3.douyinvod.com/live.mp4"}]}}]}"""
    @Test fun noteWithNoLiveHintsStillRecoversClip() {
        assertNull(DouyinPage.parse(page,link).imageVideos.single())
        val info=DouyinPage.supplementNote(page,web,link)
        assertEquals("https://v3.douyinvod.com/live.mp4",info.imageVideos.single())
        assertTrue(info.separateAlbumMusic)
    }
    @Test fun missingClipOrMismatchedPhotoCannotSilentlyBecomeStatic() {
        assertThrows(IllegalStateException::class.java) { DouyinPage.supplementNote(page,web.replace("photo-one","other-photo"),link) }
        assertThrows(IllegalStateException::class.java) { DouyinPage.supplementNote(page,web.replace("https://v3.douyinvod.com/live.mp4",""),link) }
    }
    @Test fun ordinaryPhotoKeepsOriginalBehavior() {
        val info=DouyinPage.supplementNote(page,"""{"awemeId":"7693082514630853989","images":[{"uri":"photo-one"}]}""",link)
        assertNull(info.imageVideos.single()); assertFalse(info.separateAlbumMusic)
    }
}
