package com.rite.pillcounting.core.scanning.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GetNdcRequestModelTest {

    private fun model() = GetNdcRequestModel(target_ndc = "t1", scanned_ndc = "s1")

    @Test
    fun getters_returnValues() {
        val m = model()
        assertEquals("t1", m.target_ndc)
        assertEquals("s1", m.scanned_ndc)
    }

    @Test
    fun equalsHashCode_equal() {
        assertEquals(model(), model())
        assertEquals(model().hashCode(), model().hashCode())
    }

    @Test
    fun equals_notEqual() {
        assertNotEquals(model(), model().copy(target_ndc = "x"))
    }

    @Test
    fun toString_containsField() {
        assertTrue(model().toString().contains("t1"))
    }

    @Test
    fun copy_overrides() {
        assertEquals("x", model().copy(scanned_ndc = "x").scanned_ndc)
    }

    @Test
    fun componentN_returnValues() {
        val m = model()
        assertEquals("t1", m.component1())
        assertEquals("s1", m.component2())
    }
}
