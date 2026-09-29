package com.daxiaamu.dbdown

import org.junit.Assert.*
import org.junit.Test

class AlbumTimingTest {
    @Test fun singleImageKeepsFullMusic() {
        assertEquals(listOf(125000L), albumImageDurations(1, 125000L))
    }
    @Test fun distributesDurationWithoutLosingRemainder() {
        assertEquals(listOf(3334L, 3334L, 3333L), albumImageDurations(3, 10001L))
    }
    @Test fun shortMusicDoesNotEnforceThreeSeconds() {
        assertEquals(listOf(500L, 500L), albumImageDurations(2, 1000L))
    }
    @Test fun missingMusicAlwaysSavesImages() {
        assertEquals(AlbumMode.IMAGES, effectiveAlbumMode(AlbumMode.VIDEO, true, null))
        assertEquals(AlbumMode.IMAGES, effectiveAlbumMode(AlbumMode.VIDEO, true, ""))
        assertEquals(AlbumMode.IMAGES, effectiveAlbumMode(AlbumMode.IMAGES, true, "https://example.com/audio"))
        assertEquals(AlbumMode.VIDEO, effectiveAlbumMode(AlbumMode.VIDEO, true, "https://example.com/audio"))
    }
}
