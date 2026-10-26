package com.daxiaamu.dbdown

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageTimeTagsTest {
    @Test fun includesLocalTimeOffsetAndSubseconds() {
        val instant = Instant.parse("2026-10-24T18:20:30.123Z").toEpochMilli()
        assertEquals(ImageTimeTags("2026:10:25 02:20:30", "+08:00", "123"),
            imageTimeTags(instant, ZoneId.of("Asia/Shanghai")))
        assertEquals(ImageTimeTags("2026:10:24 18:20:30", "+00:00", "123"),
            imageTimeTags(instant, ZoneId.of("UTC")))
    }

    @Test fun usesDaylightSavingOffsetAtDownloadTime() {
        val instant = Instant.parse("2026-07-01T00:00:00.007Z").toEpochMilli()
        assertEquals(ImageTimeTags("2026:06:30 20:00:00", "-04:00", "007"),
            imageTimeTags(instant, ZoneId.of("America/New_York")))
    }
}
