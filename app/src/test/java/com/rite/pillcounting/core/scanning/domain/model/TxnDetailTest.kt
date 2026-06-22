package com.rite.pillcounting.core.scanning.domain.model

import com.rite.pillcounting.core.models.StepState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TxnDetailTest {

    private fun full() = TxnDetail(
        txnDetailId = 1L,
        batchNumber = 5,
        count = 30,
        createdAt = 1000L,
        image = "img.png",
        type = StepState.SCAN
    )

    @Test
    fun default_batchNumber_isZero() {
        val t = TxnDetail(
            txnDetailId = 1L,
            count = 1,
            createdAt = 0L,
            image = null,
            type = StepState.VIAL
        )
        assertEquals(0, t.batchNumber)
        assertNull(t.image)
    }

    @Test
    fun getters_returnValues() {
        val t = full()
        assertEquals(1L, t.txnDetailId)
        assertEquals(5, t.batchNumber)
        assertEquals(30, t.count)
        assertEquals(1000L, t.createdAt)
        assertEquals("img.png", t.image)
        assertEquals(StepState.SCAN, t.type)
    }

    @Test
    fun equalsHashCode_equal() {
        assertEquals(full(), full())
        assertEquals(full().hashCode(), full().hashCode())
    }

    @Test
    fun equals_notEqual() {
        assertNotEquals(full(), full().copy(count = 99))
    }

    @Test
    fun toString_containsField() {
        assertTrue(full().toString().contains("img.png"))
    }

    @Test
    fun copy_overrides() {
        assertEquals(99, full().copy(count = 99).count)
    }

    @Test
    fun componentN_returnValues() {
        val t = full()
        assertEquals(1L, t.component1())
        assertEquals(5, t.component2())
        assertEquals(30, t.component3())
        assertEquals(1000L, t.component4())
        assertEquals("img.png", t.component5())
        assertEquals(StepState.SCAN, t.component6())
    }
}
