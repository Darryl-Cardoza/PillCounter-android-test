package com.dispensesure.retail.core.room.models.dtos

import com.dispensesure.retail.core.room.models.enums.CountStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DispenseStatusCountTest {

    private fun sample() = DispenseStatusCount(
        status = CountStatus.COMPLETED,
        cnt = 7
    )

    @Test
    fun getters() {
        val d = sample()
        assertEquals(CountStatus.COMPLETED, d.status)
        assertEquals(7, d.cnt)
    }

    @Test
    fun equalsHashCode() {
        assertEquals(sample(), sample())
        assertEquals(sample().hashCode(), sample().hashCode())
        assertNotEquals(sample(), sample().copy(cnt = 8))
    }

    @Test
    fun toString_containsField() {
        assertTrue(sample().toString().contains("cnt=7"))
    }

    @Test
    fun copy() {
        assertEquals(9, sample().copy(cnt = 9).cnt)
    }

    @Test
    fun componentN() {
        val d = sample()
        assertEquals(CountStatus.COMPLETED, d.component1())
        assertEquals(7, d.component2())
    }
}
