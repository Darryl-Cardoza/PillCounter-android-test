package com.rite.pillcounting.feature.profile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StateDataTest {

    @Test
    fun statesFor_us_isNotEmpty() {
        assertTrue(StateData.statesFor("US").isNotEmpty())
    }

    @Test
    fun statesFor_ca_isNotEmpty() {
        assertTrue(StateData.statesFor("CA").isNotEmpty())
    }

    @Test
    fun statesFor_unknownCountry_isEmpty() {
        assertTrue(StateData.statesFor("XX").isEmpty())
    }

    @Test
    fun statesFor_null_isEmpty() {
        assertTrue(StateData.statesFor(null).isEmpty())
    }

    @Test
    fun statesFor_us_haveUniqueCodes() {
        val codes = StateData.statesFor("US").map { it.code }
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun statesFor_ca_haveUniqueCodes() {
        val codes = StateData.statesFor("CA").map { it.code }
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun statesFor_us_containsCalifornia() {
        val california = StateData.statesFor("US").firstOrNull { it.code == "CA" }
        assertEquals("California", california?.name)
    }

    @Test
    fun statesFor_ca_containsOntario() {
        val ontario = StateData.statesFor("CA").firstOrNull { it.code == "ON" }
        assertEquals("Ontario", ontario?.name)
    }
}
