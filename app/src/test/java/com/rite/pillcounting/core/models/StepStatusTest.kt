package com.rite.pillcounting.core.models

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class StepStatusTest {

    @Test
    fun values_containsAllConstantsInOrder() {
        val expected = arrayOf(
            StepStatus.DONE,
            StepStatus.ACTIVE,
            StepStatus.PENDING
        )
        assertArrayEquals(expected, StepStatus.values())
        assertEquals(3, StepStatus.values().size)
    }

    @Test
    fun valueOf_eachConstant() {
        assertEquals(StepStatus.DONE, StepStatus.valueOf("DONE"))
        assertEquals(StepStatus.ACTIVE, StepStatus.valueOf("ACTIVE"))
        assertEquals(StepStatus.PENDING, StepStatus.valueOf("PENDING"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun valueOf_invalid_throws() {
        StepStatus.valueOf("UNKNOWN")
    }
}
