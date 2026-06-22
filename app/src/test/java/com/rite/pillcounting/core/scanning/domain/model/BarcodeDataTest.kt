package com.rite.pillcounting.core.scanning.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BarcodeDataTest {

    private fun full() = BarcodeData(
        gtin = "00312345678906",
        lotNumber = "LOT1",
        serialNumber = "SER1",
        productionDate = LocalDate.of(2020, 1, 1),
        packingDate = LocalDate.of(2020, 2, 2),
        sellByDate = LocalDate.of(2020, 3, 3),
        expirationDate = LocalDate.of(2025, 12, 31),
        netWeightKg = 1.0,
        grossWeightKg = 2.0,
        netWeightLb = 3.0,
        grossWeightLb = 4.0
    )

    @Test
    fun defaults_areNull() {
        val d = BarcodeData()
        assertNull(d.gtin)
        assertNull(d.lotNumber)
        assertNull(d.serialNumber)
        assertNull(d.productionDate)
        assertNull(d.packingDate)
        assertNull(d.sellByDate)
        assertNull(d.expirationDate)
        assertNull(d.netWeightKg)
        assertNull(d.grossWeightKg)
        assertNull(d.netWeightLb)
        assertNull(d.grossWeightLb)
    }

    @Test
    fun getters_returnValues() {
        val d = full()
        assertEquals("00312345678906", d.gtin)
        assertEquals("LOT1", d.lotNumber)
        assertEquals("SER1", d.serialNumber)
        assertEquals(LocalDate.of(2020, 1, 1), d.productionDate)
        assertEquals(LocalDate.of(2020, 2, 2), d.packingDate)
        assertEquals(LocalDate.of(2020, 3, 3), d.sellByDate)
        assertEquals(LocalDate.of(2025, 12, 31), d.expirationDate)
        assertEquals(1.0, d.netWeightKg!!, 0.0)
        assertEquals(2.0, d.grossWeightKg!!, 0.0)
        assertEquals(3.0, d.netWeightLb!!, 0.0)
        assertEquals(4.0, d.grossWeightLb!!, 0.0)
    }

    @Test
    fun equalsHashCode_equal() {
        assertEquals(full(), full())
        assertEquals(full().hashCode(), full().hashCode())
    }

    @Test
    fun equals_notEqual() {
        assertNotEquals(full(), full().copy(gtin = "other"))
    }

    @Test
    fun toString_containsField() {
        assertTrue(full().toString().contains("LOT1"))
    }

    @Test
    fun copy_overrides() {
        assertEquals("x", full().copy(lotNumber = "x").lotNumber)
    }

    @Test
    fun componentN_returnValues() {
        val d = full()
        assertEquals("00312345678906", d.component1())
        assertEquals("LOT1", d.component2())
        assertEquals("SER1", d.component3())
        assertEquals(LocalDate.of(2020, 1, 1), d.component4())
        assertEquals(LocalDate.of(2020, 2, 2), d.component5())
        assertEquals(LocalDate.of(2020, 3, 3), d.component6())
        assertEquals(LocalDate.of(2025, 12, 31), d.component7())
        assertEquals(1.0, d.component8()!!, 0.0)
        assertEquals(2.0, d.component9()!!, 0.0)
        assertEquals(3.0, d.component10()!!, 0.0)
        assertEquals(4.0, d.component11()!!, 0.0)
    }
}
