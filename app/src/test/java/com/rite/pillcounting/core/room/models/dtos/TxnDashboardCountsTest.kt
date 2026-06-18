package com.rite.pillcounting.core.room.models.dtos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TxnDashboardCountsTest {

    private fun sample() = TxnDashboardCounts(
        completedFixed = 1,
        partialFixed = 2,
        completedRegular = 3,
        partialRegular = 4
    )

    @Test
    fun getters() {
        val d = sample()
        assertEquals(1, d.completedFixed)
        assertEquals(2, d.partialFixed)
        assertEquals(3, d.completedRegular)
        assertEquals(4, d.partialRegular)
    }

    @Test
    fun equalsHashCode() {
        assertEquals(sample(), sample())
        assertEquals(sample().hashCode(), sample().hashCode())
        assertNotEquals(sample(), sample().copy(completedFixed = 9))
    }

    @Test
    fun toString_containsField() {
        assertTrue(sample().toString().contains("completedFixed=1"))
    }

    @Test
    fun copy() {
        assertEquals(9, sample().copy(partialRegular = 9).partialRegular)
    }

    @Test
    fun componentN() {
        val d = sample()
        assertEquals(1, d.component1())
        assertEquals(2, d.component2())
        assertEquals(3, d.component3())
        assertEquals(4, d.component4())
    }
}
