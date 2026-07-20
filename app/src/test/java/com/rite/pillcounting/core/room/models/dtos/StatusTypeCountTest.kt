package com.rite.pillcounting.core.room.models.dtos

import com.rite.pillcounting.core.room.models.enums.CountStatus
import com.rite.pillcounting.core.room.models.enums.CountType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusTypeCountTest {

    private fun sample() = StatusTypeCount(
        status = CountStatus.COMPLETED,
        isDispense = true,
        cnt = 7
    )

    @Test
    fun getters() {
        val d = sample()
        assertEquals(CountStatus.COMPLETED, d.status)
        assertEquals(true, d.isDispense)
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
        assertEquals(false, sample().copy(isDispense = false).isDispense)
    }

    @Test
    fun componentN() {
        val d = sample()
        assertEquals(CountStatus.COMPLETED, d.component1())
        assertEquals(true, d.component2())
        assertEquals(7, d.component3())
    }
}
