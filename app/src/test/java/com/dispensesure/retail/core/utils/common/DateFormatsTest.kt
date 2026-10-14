package com.dispensesure.retail.core.utils.common

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Unit tests for the [DateFormats] constants object. */
class DateFormatsTest {

    @Test
    fun displayConstants_haveUsValues() {
        assertEquals("MM/dd/yy", DateFormats.MM_DD_YY)
        assertEquals("MM/dd/yy hh:mm a", DateFormats.MM_DD_YY_HH_MM_A)
        assertEquals("hh:mm a", DateFormats.HH_MM_A)
        assertEquals("MMM dd, yyyy", DateFormats.MMM_DD_YYYY)
        assertEquals("MMM dd, yyyy hh:mm a", DateFormats.MMM_DD_YYYY_HH_MM_A)
    }

    @Test
    fun expiryStored_formatsFourDigitYear() {
        assertEquals("12-31-2027", LocalDate.of(2027, 12, 31).format(DateTimeFormatter.ofPattern(DateFormats.EXPIRY_STORED)))
    }

    @Test
    fun machineConstants_keepWireValues() {
        assertEquals("yyyyMMddHHmmss", DateFormats.HL7_TIMESTAMP)
        assertEquals("yyMMdd", DateFormats.GS1_YYMMDD)
        assertEquals("yyyy-MM-dd'T'HH:mm:ss", DateFormats.ISO_UTC_NO_SUFFIX)
        assertEquals("yyyy-MM-dd HH:mm:ss.SSS", DateFormats.LOG_TIMESTAMP)
        assertEquals("yyyyMMdd_HHmmss", DateFormats.FILE_TIMESTAMP)
        assertEquals("yyyy-MM-dd_HH-mm-ss", DateFormats.IMAGE_FILE_TIMESTAMP)
    }

    @Test
    fun inputFormats_storedExpiryOnly() {
        assertEquals(
            listOf(DateFormats.EXPIRY_STORED),
            DateFormats.INPUT_FORMATS
        )
    }
}
