package com.rite.pillcounting.core.utils.common

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [HistoryRetention].
 *
 * Covers optionsDays content and every branch of getTrailingText, including the
 * else fallback and the null-getOrNull fallback (short displayStrings list).
 */
class HistoryRetentionTest {

    private val labels = listOf("1 week", "15 days", "1 month", "2 months", "3 months")

    @Test
    fun optionsDays_hasExpectedValues() {
        assertEquals(listOf(7, 15, 30, 60, 90), HistoryRetention.optionsDays)
    }

    @Test
    fun getTrailingText_mapsEachKnownDuration() {
        assertEquals("1 week", HistoryRetention.getTrailingText(7, labels))
        assertEquals("15 days", HistoryRetention.getTrailingText(15, labels))
        assertEquals("1 month", HistoryRetention.getTrailingText(30, labels))
        assertEquals("2 months", HistoryRetention.getTrailingText(60, labels))
        assertEquals("3 months", HistoryRetention.getTrailingText(90, labels))
    }

    @Test
    fun getTrailingText_unknownDuration_usesFallback() {
        assertEquals("45 days", HistoryRetention.getTrailingText(45, labels))
    }

    @Test
    fun getTrailingText_knownDurationButMissingLabel_usesFallback() {
        // displayStrings too short -> getOrNull returns null -> "$days days" fallback
        assertEquals("90 days", HistoryRetention.getTrailingText(90, emptyList()))
        assertEquals("30 days", HistoryRetention.getTrailingText(30, listOf("only-one")))
    }
}
