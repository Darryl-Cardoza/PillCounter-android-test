package com.dispensesure.retail.core.region

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionTest {

    @Test
    fun `US and CA map to their region`() {
        assertEquals(Region.US, Region.fromCountryCode("US"))
        assertEquals(Region.CA, Region.fromCountryCode("CA"))
    }

    @Test
    fun `country code is matched ignoring case and spaces`() {
        assertEquals(Region.CA, Region.fromCountryCode(" ca "))
    }

    @Test
    fun `unknown, blank and null country codes have no region`() {
        assertNull(Region.fromCountryCode("MX"))
        assertNull(Region.fromCountryCode(""))
        assertNull(Region.fromCountryCode(null))
    }

    @Test
    fun `US has no DINs and accepts 12 to 14 digit plain codes`() {
        assertFalse(Region.US.eightDigitCodesAreDins)
        assertEquals(setOf(12, 13, 14), Region.US.plainProductCodeLengths)
    }

    @Test
    fun `canada treats 8-digit codes as DINs and accepts 8, 12, 13 and 14 digit plain codes`() {
        assertTrue(Region.CA.eightDigitCodesAreDins)
        assertEquals(setOf(8, 12, 13, 14), Region.CA.plainProductCodeLengths)
    }

    @Test
    fun `only exactly 8 digits is a DIN`() {
        assertTrue("02230714".isDin())
        assertFalse("0223071".isDin())
        assertFalse("022307140".isDin())
        assertFalse("0223071A".isDin())
    }

    @Test
    fun `expiry is shown month first in the US and ISO in canada`() {
        assertEquals("07/31/28", Region.US.formatExpiry("07-31-2028"))
        assertEquals("2028-07-31", Region.CA.formatExpiry("07-31-2028"))
    }

    @Test
    fun `only canada HL7 orders accept a DIN`() {
        assertFalse(Region.US.hl7OrdersAcceptDin)
        assertTrue(Region.CA.hl7OrdersAcceptDin)
    }

    @Test
    fun `only US shows buckets`() {
        assertTrue(Region.US.showsBucket)
        assertFalse(Region.CA.showsBucket)
    }

    @Test
    fun `US dates are month first, canada dates are ISO`() {
        assertEquals("MM/dd/yy", Region.US.numericDatePattern)
        assertEquals("MM/dd/yy hh:mm a", Region.US.numericDateTimePattern)
        assertEquals("yyyy-MM-dd", Region.CA.numericDatePattern)
        assertEquals("yyyy-MM-dd hh:mm a", Region.CA.numericDateTimePattern)
    }
}
