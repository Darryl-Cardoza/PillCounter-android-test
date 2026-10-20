package com.dispensesure.retail.core.region

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
