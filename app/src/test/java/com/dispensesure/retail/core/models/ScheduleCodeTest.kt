package com.dispensesure.retail.core.models

import com.dispensesure.retail.core.region.Region
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleCodeTest {

    @Test
    fun `US schedules are CII to CVI in order`() {
        assertEquals(
            listOf(ScheduleCode.CII, ScheduleCode.CIII, ScheduleCode.CIV, ScheduleCode.CV, ScheduleCode.CVI),
            ScheduleCode.forRegion(Region.US),
        )
    }

    @Test
    fun `canada schedules are C, NA and TS in order`() {
        assertEquals(listOf(ScheduleCode.C, ScheduleCode.NA, ScheduleCode.TS), ScheduleCode.forRegion(Region.CA))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `valueOf an unknown name throws`() {
        ScheduleCode.valueOf("UNKNOWN")
    }

    @Test
    fun `US drug types match US schedules only, ignoring case and spaces`() {
        assertTrue(isControlledDrugType(" cii ", Region.US))
        assertFalse(isControlledDrugType("NA", Region.US))
        assertFalse(isControlledDrugType("TS", Region.US))
    }

    @Test
    fun `canada drug types match canada schedules only`() {
        assertTrue(isControlledDrugType("C", Region.CA))
        assertTrue(isControlledDrugType("na", Region.CA))
        assertTrue(isControlledDrugType("TS", Region.CA))
        assertFalse(isControlledDrugType("CII", Region.CA))
    }

    @Test
    fun `missing or other drug types are not controlled`() {
        assertFalse(isControlledDrugType(null, Region.US))
        assertFalse(isControlledDrugType("", Region.CA))
        assertFalse(isControlledDrugType("TABLET", Region.CA))
    }
}
