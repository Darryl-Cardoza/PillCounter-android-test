package com.dispensesure.retail.core.utils.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    // parseUtcIsoToEpochMs  —  input: "yyyy-MM-dd'T'HH:mm:ss" (± .SSS / Z / +HH:mm)
    // -------------------------------------------------------------------------

    // DATE_017
    @Test
    fun `parseUtcIsoToEpochMs returns null for null input`() {
        assertNull(DateUtils.parseUtcIsoToEpochMs(null))
    }

    // DATE_018
    @Test
    fun `parseUtcIsoToEpochMs returns null for blank input`() {
        assertNull(DateUtils.parseUtcIsoToEpochMs("   "))
    }

    // DATE_019
    @Test
    fun `parseUtcIsoToEpochMs returns null for garbage input`() {
        assertNull(DateUtils.parseUtcIsoToEpochMs("not-a-date"))
    }

    // DATE_020
    @Test
    fun `parseUtcIsoToEpochMs parses plain ISO-8601 without suffix`() {
        assertEquals(1_736_944_245_000L, DateUtils.parseUtcIsoToEpochMs("2025-01-15T12:30:45"))
    }

    // DATE_021
    @Test
    fun `parseUtcIsoToEpochMs parses ISO-8601 with trailing Z`() {
        assertEquals(1_736_944_245_000L, DateUtils.parseUtcIsoToEpochMs("2025-01-15T12:30:45Z"))
    }

    // DATE_022
    @Test
    fun `parseUtcIsoToEpochMs parses ISO-8601 with fractional seconds`() {
        // Sub-second precision is discarded — truncated to the second boundary.
        assertEquals(1_736_944_245_000L, DateUtils.parseUtcIsoToEpochMs("2025-01-15T12:30:45.123Z"))
    }

    // DATE_023
    @Test
    fun `parseUtcIsoToEpochMs parses ISO-8601 with timezone offset`() {
        // Offset is stripped and the leading portion is treated as UTC — matches the
        // controller's prior behavior so the migration is drift-free.
        assertEquals(1_736_944_245_000L, DateUtils.parseUtcIsoToEpochMs("2025-01-15T12:30:45+05:30"))
    }
}
