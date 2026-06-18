package com.rite.pillcounting.core.models

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleCodeTest {

    @Test
    fun values_containsAllConstantsInOrder() {
        val expected = arrayOf(
            ScheduleCode.CII,
            ScheduleCode.CIII,
            ScheduleCode.CIV,
            ScheduleCode.CV,
            ScheduleCode.CVI
        )
        assertArrayEquals(expected, ScheduleCode.values())
        assertEquals(5, ScheduleCode.values().size)
    }

    @Test
    fun valueOf_eachConstant() {
        assertEquals(ScheduleCode.CII, ScheduleCode.valueOf("CII"))
        assertEquals(ScheduleCode.CIII, ScheduleCode.valueOf("CIII"))
        assertEquals(ScheduleCode.CIV, ScheduleCode.valueOf("CIV"))
        assertEquals(ScheduleCode.CV, ScheduleCode.valueOf("CV"))
        assertEquals(ScheduleCode.CVI, ScheduleCode.valueOf("CVI"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun valueOf_invalid_throws() {
        ScheduleCode.valueOf("UNKNOWN")
    }

    @Test
    fun ordinals_areSequential() {
        assertEquals(0, ScheduleCode.CII.ordinal)
        assertEquals(4, ScheduleCode.CVI.ordinal)
    }
}
