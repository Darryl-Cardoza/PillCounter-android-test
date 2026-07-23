package com.rite.pillcounting.feature.profile.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CountryTest {

    @Test
    fun getters_returnValues() {
        val country = Country(code = "US", name = "United States")
        assertEquals("US", country.code)
        assertEquals("United States", country.name)
    }

    @Test
    fun equals_and_hashCode_forSameValues() {
        val a = Country("US", "United States")
        val b = Country("US", "United States")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun copy_overridesCode() {
        val country = Country("US", "United States")
        assertEquals("CA", country.copy(code = "CA").code)
    }
}
