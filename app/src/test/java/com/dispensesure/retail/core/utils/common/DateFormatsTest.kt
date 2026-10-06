package com.dispensesure.retail.core.utils.common

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for the [DateFormats] constants object. */
class DateFormatsTest {

    @Test
    fun displayConstants_haveUsValues() {
        assertEquals("MM/dd/yy", DateFormats.MM_DD_YY)
        assertEquals("MM/dd/yy hh:mm a", DateFormats.MM_DD_YY_HH_MM_A)
        assertEquals("hh:mm a", DateFormats.HH_MM_A)
        assertEquals("MMM dd, yyyy", DateFormats.MMM_DD_YYYY)
        assertEquals("MMM dd, yyyy hh:mm a", DateFormats.MMM_DD_YYYY_HH_MM_A)
        assertEquals("MM-dd-yyyy", DateFormats.LEGACY_MM_DD_YYYY)
    }

    @Test
    fun machineConstants_keepWireValues() {
        assertEquals("yyyyMMddHHmmss", DateFormats.HL7_TIMESTAMP)
        assertEquals("yyMMdd", DateFormats.GS1_YYMMDD)
        assertEquals("yyyy-MM-dd'T'HH:mm:ss'Z'", DateFormats.ISO_UTC)
        assertEquals("yyyy-MM-dd'T'HH:mm:ss", DateFormats.ISO_UTC_NO_SUFFIX)
        assertEquals("yyyy-MM-dd HH:mm:ss.SSS", DateFormats.LOG_TIMESTAMP)
        assertEquals("yyyyMMdd_HHmmss", DateFormats.FILE_TIMESTAMP)
        assertEquals("yyyy-MM-dd_HH-mm-ss", DateFormats.IMAGE_FILE_TIMESTAMP)
    }

    @Test
    fun inputFormats_newExpiryThenLegacy() {
        assertEquals(
            listOf(DateFormats.MM_DD_YY, DateFormats.LEGACY_MM_DD_YYYY),
            DateFormats.INPUT_FORMATS
        )
    }
}
