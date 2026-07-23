package com.rite.pillcounting.feature.profile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CountryDataTest {

    @Test
    fun countries_isNotEmpty() {
        assertTrue(CountryData.countries.isNotEmpty())
    }

    @Test
    fun countries_containsDefaultCountry() {
        val default = CountryData.countries.firstOrNull {
            it.code == CountryData.DEFAULT_COUNTRY_CODE
        }
        assertEquals("US", default?.code)
    }

    @Test
    fun countries_haveUniqueCodes() {
        val codes = CountryData.countries.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun defaultCountryCode_isUS() {
        assertEquals("US", CountryData.DEFAULT_COUNTRY_CODE)
    }
}
