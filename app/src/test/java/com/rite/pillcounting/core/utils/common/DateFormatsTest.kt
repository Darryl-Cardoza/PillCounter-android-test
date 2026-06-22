package com.rite.pillcounting.core.utils.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the [DateFormats] constants object. */
class DateFormatsTest {

    @Test
    fun constants_haveExpectedValues() {
        assertEquals("MM-dd-yyyy", DateFormats.MM_DD_YYYY)
        assertEquals("MM-dd-yyyy hh:mm a", DateFormats.MM_DD_YYYY_HH_MM_A)
    }

    @Test
    fun inputFormats_containsBothPatternsInOrder() {
        assertEquals(2, DateFormats.INPUT_FORMATS.size)
        assertEquals(DateFormats.MM_DD_YYYY, DateFormats.INPUT_FORMATS[0])
        assertEquals(DateFormats.MM_DD_YYYY_HH_MM_A, DateFormats.INPUT_FORMATS[1])
        assertTrue(DateFormats.INPUT_FORMATS.contains(DateFormats.MM_DD_YYYY))
    }
}
