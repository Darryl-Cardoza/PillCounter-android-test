package com.rite.pillcounting.feature.profile.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class StateTest {

    @Test
    fun getters_returnValues() {
        val state = State(code = "CA", name = "California")
        assertEquals("CA", state.code)
        assertEquals("California", state.name)
    }

    @Test
    fun equals_and_hashCode_forSameValues() {
        val a = State("CA", "California")
        val b = State("CA", "California")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun copy_overridesCode() {
        val state = State("CA", "California")
        assertEquals("AB", state.copy(code = "AB").code)
    }
}
