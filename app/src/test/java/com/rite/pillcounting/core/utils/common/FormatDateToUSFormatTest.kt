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
        assertEquals("02-29-2024", formatDateToUSFormat("02-29-2024"))
    }

    @Test
    fun leapDayOnNonLeapYear_rejectedByStrictParsing_returnsCleanInput() {
        // 2023 is not a leap year; Feb 29 must be rejected under isLenient = false.
        assertEquals("02-29-2023", formatDateToUSFormat("02-29-2023"))
    }

    @Test
    fun startOfYearBoundary_parsesSuccessfully() {
        assertEquals("01-01-2024", formatDateToUSFormat("01-01-2024"))
    }

    @Test
    fun dateTimeFormat_invalidHourValue_returnsCleanInput() {
        // Hour 25 is invalid for hh (1-12) pattern; both formats must fail to parse.
        assertEquals("12-31-2024 25:00 AM", formatDateToUSFormat("12-31-2024 25:00 AM"))
    }

    @Test
    fun malformedOutputPattern_exceptionCaught_returnsOriginalUntrimmedInput() {
        // SimpleDateFormat throws for unknown pattern letters (e.g. 'q' repeated oddly);
        // the catch block must return the original, untrimmed input, not cleanInput.
        val result = formatDateToUSFormat("  12-31-2024  ", "qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq")
        assertEquals("  12-31-2024  ", result)
    }

    @Test
    fun customOutputPattern_appliedToDateTimeInput() {
        val result = formatDateToUSFormat("12-31-2024 10:30 AM", "yyyy-MM-dd")
        assertEquals("2024-12-31", result)
    }
}
