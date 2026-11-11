package com.dispensesure.retail.core.analytics

import com.dispensesure.retail.core.analytics.store.AnalyticsDateBoundary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

class AnalyticsDateBoundaryTest {

    private val vancouver = ZoneId.of("America/Vancouver")

    @Test
    fun `businessDate formats in ISO even under Thai locale`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale("th", "TH"))
            // 2024-03-15 noon UTC → same date in Vancouver (UTC-7)
            val noonUtcMs = java.time.Instant.parse("2024-03-15T19:00:00Z").toEpochMilli()
            val boundary = AnalyticsDateBoundary(vancouver)
            assertEquals("2024-03-15", boundary.businessDate(noonUtcMs))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `dayRangeMillis spans 23 hours on DST spring-forward day`() {
        // 2024-03-10: Vancouver springs forward 2am→3am — 23-hour day
        val boundary = AnalyticsDateBoundary(vancouver)
        val range = boundary.dayRangeMillis("2024-03-10")
        assertNotNull(range)
        val expectedMs = 23L * 60 * 60 * 1000
        assertEquals(expectedMs, range!!.second - range.first)
    }

    @Test
    fun `dayRangeMillis spans 25 hours on DST fall-back day`() {
        // 2024-11-03: Vancouver falls back 2am→1am — 25-hour day
        val boundary = AnalyticsDateBoundary(vancouver)
        val range = boundary.dayRangeMillis("2024-11-03")
        assertNotNull(range)
        val expectedMs = 25L * 60 * 60 * 1000
        assertEquals(expectedMs, range!!.second - range.first)
    }

    @Test
    fun `dayRangeMillis returns null for unparseable date`() {
        val boundary = AnalyticsDateBoundary(vancouver)
        assertNull(boundary.dayRangeMillis("not-a-date"))
    }

    @Test
    fun `timezoneOffsetMinutes is positive east of UTC`() {
        // UTC+5:30 (India) → 330 minutes
        val kolkata = ZoneId.of("Asia/Kolkata")
        val boundary = AnalyticsDateBoundary(kolkata)
        val nowMs = java.time.Instant.parse("2024-06-01T12:00:00Z").toEpochMilli()
        assertEquals(330, boundary.timezoneOffsetMinutes(nowMs))
    }

    @Test
    fun `timezoneOffsetMinutes is negative west of UTC`() {
        // UTC-7 (Vancouver summer)
        val boundary = AnalyticsDateBoundary(vancouver)
        val nowMs = java.time.Instant.parse("2024-06-01T12:00:00Z").toEpochMilli()
        assertEquals(-420, boundary.timezoneOffsetMinutes(nowMs))
    }

    @Test
    fun `businessDate uses local time not UTC`() {
        // 2024-03-15 03:00 UTC = 2024-03-14 20:00 in Vancouver — previous day
        val boundary = AnalyticsDateBoundary(vancouver)
        val earlyUtcMs = java.time.Instant.parse("2024-03-15T03:00:00Z").toEpochMilli()
        assertEquals("2024-03-14", boundary.businessDate(earlyUtcMs))
    }
}
