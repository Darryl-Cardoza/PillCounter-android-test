package com.dispensesure.retail.core.utils.common

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the top-level [formatDateToUSFormat] function.
 *
 * Covers: new MM/dd/yy and legacy MM-dd-yyyy expiry text, unparseable input
 * (returned trimmed), custom output pattern, and the blank-input passthrough.
 */
class FormatDateToUSFormatTest {

    @Test
    fun legacyExpiry_returnsMmDdYy() {
        assertEquals("12/31/27", formatDateToUSFormat("12-31-2027"))
    }

    @Test
    fun newExpiry_returnedUnchanged() {
        assertEquals("12/31/27", formatDateToUSFormat("12/31/27"))
    }

    @Test
    fun trimsWhitespaceBeforeParsing() {
        assertEquals("01/02/23", formatDateToUSFormat("  01-02-2023  "))
    }

    @Test
    fun unparseableInput_returnsTrimmedCleanInput() {
        // No format matches -> falls through to returning cleanInput.
        assertEquals("not-a-date", formatDateToUSFormat("  not-a-date  "))
    }

    @Test
    fun dashPlaceholder_returnedUnchanged() {
        assertEquals("—", formatDateToUSFormat("—"))
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

    @Test
    fun invalidMonthValue_rejectedByStrictParsing_returnsCleanInput() {
        // isLenient = false must reject month 13 in both candidate formats.
        assertEquals("13-15-2024", formatDateToUSFormat("13-15-2024"))
    }

    @Test
    fun invalidDayValue_rejectedByStrictParsing_returnsCleanInput() {
        // February never has 30 days; strict parser must reject it.
        assertEquals("02-30-2024", formatDateToUSFormat("02-30-2024"))
    }

    @Test
    fun leapDayOnLeapYear_parsesSuccessfully() {
        assertEquals("02/29/24", formatDateToUSFormat("02-29-2024"))
    }

    @Test
    fun leapDayOnNonLeapYear_rejectedByStrictParsing_returnsCleanInput() {
        // 2023 is not a leap year; Feb 29 must be rejected under isLenient = false.
        assertEquals("02-29-2023", formatDateToUSFormat("02-29-2023"))
    }

    @Test
    fun startOfYearBoundary_parsesSuccessfully() {
        assertEquals("01/01/24", formatDateToUSFormat("01-01-2024"))
    }

    @Test
    fun malformedOutputPattern_exceptionCaught_returnsOriginalUntrimmedInput() {
        // SimpleDateFormat throws for unknown pattern letters (e.g. 'q' repeated oddly);
        // the catch block must return the original, untrimmed input, not cleanInput.
        val result = formatDateToUSFormat("  12-31-2024  ", "qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq")
        assertEquals("  12-31-2024  ", result)
    }
}
