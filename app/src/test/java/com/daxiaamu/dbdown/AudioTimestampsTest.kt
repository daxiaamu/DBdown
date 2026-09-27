package com.daxiaamu.dbdown
import org.junit.Assert.*
import org.junit.Test
class AudioTimestampsTest {
    @Test fun preservesNormalTimestamps() {
        val t = AudioTimestamps()
        assertEquals(0L, t.next(0))
        assertEquals(23219L, t.next(23219))
        assertEquals(46439L, t.next(46439))
    }
    @Test fun fixesObservedDashFragmentRoundingRegression() {
        val t = AudioTimestamps()
        assertEquals(1783529319L, t.next(1783529319))
        assertEquals(1783529320L, t.next(1783529297))
        assertEquals(1783552517L, t.next(1783552517))
    }
    @Test fun makesDuplicateTimestampsStrictlyIncreasing() {
        val t = AudioTimestamps()
        assertEquals(0L, t.next(0))
        assertEquals(1L, t.next(0))
    }
    @Test(expected = IllegalStateException::class) fun rejectsLargeDiscontinuitiesInsteadOfCorruptingAudio() {
        val t = AudioTimestamps()
        t.next(100000)
        t.next(0)
    }
}
