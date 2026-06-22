package com.rite.pillcounting.core.utils.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Month

/**
 * Unit tests for [DateUtils].
 *
 * All logic is pure JVM (java.time / java.text) so no Android framework is needed.
 * android.util.Log calls inside DateUtils are silenced by isReturnDefaultValues = true.
 */
class DateUtilsTest {

    // -------------------------------------------------------------------------
    // parseBarcodeDate  —  format: yyMMdd
    // -------------------------------------------------------------------------

    // DATE_001
    @Test
    fun `parseBarcodeDate returns correct LocalDate for valid yyMMdd string`() {
        val result = DateUtils.parseBarcodeDate("241231")
        assertNotNull(result)
        assertEquals(2024, result!!.year)
        assertEquals(Month.DECEMBER, result.month)
        assertEquals(31, result.dayOfMonth)
    }

    // DATE_002
    @Test
    fun `parseBarcodeDate returns correct LocalDate for another valid date`() {
        val result = DateUtils.parseBarcodeDate("251006")
        assertNotNull(result)
        assertEquals(2025, result!!.year)
        assertEquals(Month.OCTOBER, result.month)
        assertEquals(6, result.dayOfMonth)
    }

    // DATE_003
    @Test
    fun `parseBarcodeDate returns null for null input`() {
        val result = DateUtils.parseBarcodeDate(null)
        assertNull(result)
    }

    // DATE_004
    @Test
    fun `parseBarcodeDate returns null for empty string`() {
        val result = DateUtils.parseBarcodeDate("")
        assertNull(result)
    }

    // DATE_005
    @Test
    fun `parseBarcodeDate returns null for blank whitespace string`() {
        val result = DateUtils.parseBarcodeDate("   ")
        assertNull(result)
    }

    // DATE_006
    @Test
    fun `parseBarcodeDate returns null for non-numeric string`() {
        val result = DateUtils.parseBarcodeDate("ABCDEF")
        assertNull(result)
    }

    // DATE_007
    @Test
    fun `parseBarcodeDate returns null for string with wrong length`() {
        val result = DateUtils.parseBarcodeDate("2412")
        assertNull(result) // yyMMdd requires exactly 6 chars
    }

    // -------------------------------------------------------------------------
    // convertToDate  —  format yyMMdd → human-readable "d MMM yyyy"
    // -------------------------------------------------------------------------

    // DATE_008
    @Test
    fun `convertToDate returns formatted string for valid yyMMdd input`() {
        val result = DateUtils.convertToDate("251006")
        assertTrue("Expected '6 Oct 2025', got '$result'", result.contains("2025"))
        assertTrue(result.contains("Oct"))
    }

    // DATE_009
    @Test
    fun `convertToDate returns empty string for null input`() {
        assertEquals("", DateUtils.convertToDate(null))
    }

    // DATE_010
    @Test
    fun `convertToDate returns empty string for empty input`() {
        assertEquals("", DateUtils.convertToDate(""))
    }

    // DATE_011
    @Test
    fun `convertToDate returns empty string for invalid format`() {
        assertEquals("", DateUtils.convertToDate("not-a-date"))
    }

    // -------------------------------------------------------------------------
    // convertUTCTimestampToDate  —  input: "yyyy-MM-dd'T'HH:mm:ss'Z'"
    // -------------------------------------------------------------------------

    // DATE_012
    @Test
    fun `convertUTCTimestampToDate returns non-empty string for valid ISO timestamp`() {
        val result = DateUtils.convertUTCTimestampToDate("2024-12-31T10:30:00Z")
        assertTrue("Expected a formatted date, got '$result'", result.isNotEmpty())
        assertTrue(result.contains("2024"))
        assertTrue(result.contains("Dec"))
    }

    // DATE_013
    @Test
    fun `convertUTCTimestampToDate returns empty string for null input`() {
        assertEquals("", DateUtils.convertUTCTimestampToDate(null))
    }

    // DATE_014
    @Test
    fun `convertUTCTimestampToDate returns empty string for empty input`() {
        assertEquals("", DateUtils.convertUTCTimestampToDate(""))
    }

    // DATE_015
    @Test
    fun `convertUTCTimestampToDate returns empty string for malformed input`() {
        assertEquals("", DateUtils.convertUTCTimestampToDate("not-a-timestamp"))
    }

    // DATE_016
    @Test
    fun `convertUTCTimestampToDate returns empty string for blank whitespace input`() {
        assertEquals("", DateUtils.convertUTCTimestampToDate("   "))
    }
}
