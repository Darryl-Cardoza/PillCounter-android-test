package com.rite.pillcounting.core.room.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrugMasterEntityTest {

    private fun sample() = DrugMasterEntity(
        drugId = 3L,
        drugName = "Drug",
        ndc = "111-22-33",
        drugType = "tablet",
        createdAt = 1234L,
        gtin = "gtin",
        packageQty = 10,
        isHazardous = true
    )

    @Test
    fun defaultValues() {
        val e = DrugMasterEntity(ndc = "abc")
        assertEquals(0L, e.drugId)
        assertNull(e.drugName)
        assertEquals("abc", e.ndc)
        assertNull(e.drugType)
        assertTrue(e.createdAt > 0L)
        assertNull(e.gtin)
        assertEquals(0, e.packageQty)
        assertFalse(e.isHazardous)
    }

    @Test
    fun nullableNonNull() {
        val e = sample()
        assertEquals("Drug", e.drugName)
        assertEquals("tablet", e.drugType)
        assertEquals("gtin", e.gtin)
        assertEquals(10, e.packageQty)
    }

    @Test
    fun nullableNull() {
        val e = DrugMasterEntity(ndc = "x", drugName = null, drugType = null, gtin = null, packageQty = null)
        assertNull(e.drugName)
        assertNull(e.drugType)
        assertNull(e.gtin)
        assertNull(e.packageQty)
    }

    @Test
    fun getters() {
        val e = sample()
        assertEquals(3L, e.drugId)
        assertEquals("111-22-33", e.ndc)
        assertEquals(1234L, e.createdAt)
        assertTrue(e.isHazardous)
    }

    @Test
    fun equalsHashCode() {
        assertEquals(sample(), sample())
        assertEquals(sample().hashCode(), sample().hashCode())
        assertNotEquals(sample(), sample().copy(ndc = "other"))
    }

    @Test
    fun toString_containsField() {
        assertTrue(sample().toString().contains("drugId=3"))
    }

    @Test
    fun copy() {
        assertEquals("new", sample().copy(ndc = "new").ndc)
    }

    @Test
    fun componentN() {
        val e = sample()
        assertEquals(3L, e.component1())
        assertEquals("Drug", e.component2())
        assertEquals("111-22-33", e.component3())
        assertEquals("tablet", e.component4())
        assertEquals(1234L, e.component5())
        assertEquals("gtin", e.component6())
        assertEquals(10, e.component7())
        assertEquals(true, e.component8())
    }
}
