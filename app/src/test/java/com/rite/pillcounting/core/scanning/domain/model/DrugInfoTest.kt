package com.rite.pillcounting.core.scanning.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrugInfoTest {

    private fun full() = DrugInfo(
        brandName = "Brand",
        genericName = "Generic",
        ndc = "12345",
        is_ndc_equivalent = true,
        drugType = "TABLET",
        qty = 10,
        isHazardous = false
    )

    @Test
    fun defaults_applied() {
        val d = DrugInfo(
            brandName = null,
            genericName = null,
            ndc = "n",
            drugType = "t"
        )
        assertNull(d.brandName)
        assertNull(d.genericName)
        assertEquals("n", d.ndc)
        assertNull(d.is_ndc_equivalent)
        assertEquals("t", d.drugType)
        assertEquals(0, d.qty)
        assertNull(d.isHazardous)
    }

    @Test
    fun getters_returnValues() {
        val d = full()
        assertEquals("Brand", d.brandName)
        assertEquals("Generic", d.genericName)
        assertEquals("12345", d.ndc)
        assertEquals(true, d.is_ndc_equivalent)
        assertEquals("TABLET", d.drugType)
        assertEquals(10, d.qty)
        assertEquals(false, d.isHazardous)
    }

    @Test
    fun equalsHashCode_equal() {
        assertEquals(full(), full())
        assertEquals(full().hashCode(), full().hashCode())
    }

    @Test
    fun equals_notEqual() {
        assertNotEquals(full(), full().copy(ndc = "x"))
    }

    @Test
    fun toString_containsField() {
        assertTrue(full().toString().contains("Brand"))
    }

    @Test
    fun copy_overrides() {
        assertEquals("x", full().copy(ndc = "x").ndc)
    }

    @Test
    fun componentN_returnValues() {
        val d = full()
        assertEquals("Brand", d.component1())
        assertEquals("Generic", d.component2())
        assertEquals("12345", d.component3())
        assertEquals(true, d.component4())
        assertEquals("TABLET", d.component5())
        assertEquals(10, d.component6())
        assertEquals(false, d.component7())
    }
}
