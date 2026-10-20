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
        assertEquals(12..14, Region.US.plainProductCodeLengths)
    }

    @Test
    fun `canada treats 8-digit codes as DINs and accepts 8 to 14 digit plain codes`() {
        assertTrue(Region.CA.eightDigitCodesAreDins)
        assertEquals(8..14, Region.CA.plainProductCodeLengths)
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
}
