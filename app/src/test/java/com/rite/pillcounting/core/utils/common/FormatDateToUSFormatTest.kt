package com.rite.pillcounting.core.utils.common

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the top-level [formatDateToUSFormat] function.
 *
 * Covers: input already in MM-dd-yyyy, input in MM-dd-yyyy hh:mm a, unparseable input
 * (returned trimmed), custom output pattern, and the blank-input passthrough.
 */
class FormatDateToUSFormatTest {

    @Test
    fun parsesDateOnlyFormat_returnsMmDdYyyy() {
        assertEquals("12-31-2024", formatDateToUSFormat("12-31-2024"))
    }

    @Test
    fun parsesDateTimeFormat_returnsDateOnlyByDefault() {
        // Input matches second format; default output pattern is MM-dd-yyyy.
        assertEquals("12-31-2024", formatDateToUSFormat("12-31-2024 10:30 AM"))
    }

    @Test
    fun trimsWhitespaceBeforeParsing() {
        assertEquals("01-02-2023", formatDateToUSFormat("  01-02-2023  "))
    }

    @Test
    fun unparseableInput_returnsTrimmedCleanInput() {
        // No format matches -> falls through to returning cleanInput.
        assertEquals("not-a-date", formatDateToUSFormat("  not-a-date  "))
    }

    @Test
    fun partialMatchNotFullLength_returnsCleanInput() {
        // Lenient=false + position.index must equal full length; trailing junk fails both formats.
        assertEquals("12-31-2024xx", formatDateToUSFormat("12-31-2024xx"))
    }

    @Test
    fun customOutputPattern_isApplied() {
        val result = formatDateToUSFormat("12-31-2024", "yyyy/MM/dd")
        assertEquals("2024/12/31", result)
    }

    @Test
    fun emptyInput_returnsEmpty() {
        assertEquals("", formatDateToUSFormat(""))
    }
}
