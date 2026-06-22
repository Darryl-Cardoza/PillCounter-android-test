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
        countType = CountType.FIXED,
        cnt = 7
    )

    @Test
    fun getters() {
        val d = sample()
        assertEquals(CountStatus.COMPLETED, d.status)
        assertEquals(CountType.FIXED, d.countType)
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
        assertEquals(CountType.REGULAR, sample().copy(countType = CountType.REGULAR).countType)
    }

    @Test
    fun componentN() {
        val d = sample()
        assertEquals(CountStatus.COMPLETED, d.component1())
        assertEquals(CountType.FIXED, d.component2())
        assertEquals(7, d.component3())
    }
}
