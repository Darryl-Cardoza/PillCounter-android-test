package com.rite.pillcounting.core.room.models.dtos

import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.room.models.enums.TxnPriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PillCountWithDrugAndTotalTest {

    private fun sample() = PillCountWithDrugAndTotal(
        txnId = 1L,
        drugName = "Drug",
        ndc = "111",
        drugType = "tablet",
        bucketId = "bucket",
        createdAt = 100L,
        targetCount = 50,
        barcodeImage = "bc.png",
        totalPillCount = 30,
        isComingFromHL7 = true,
        isNdcVerified = true,
        countType = CountType.FIXED,
        priority = TxnPriority.High,
        isHazardous = true
    )

    @Test
    fun defaultValue_isHazardous() {
        val d = PillCountWithDrugAndTotal(
            txnId = 1L,
            drugName = null,
            ndc = null,
            drugType = null,
            bucketId = null,
            createdAt = 0L,
            targetCount = null,
            barcodeImage = null,
            totalPillCount = 0,
            isComingFromHL7 = false,
            isNdcVerified = false,
            countType = CountType.REGULAR,
            priority = null
        )
        assertFalse(d.isHazardous)
        assertNull(d.drugName)
        assertNull(d.ndc)
        assertNull(d.drugType)
        assertNull(d.bucketId)
        assertNull(d.targetCount)
        assertNull(d.barcodeImage)
        assertNull(d.priority)
    }

    @Test
    fun getters() {
        val d = sample()
        assertEquals(1L, d.txnId)
        assertEquals("Drug", d.drugName)
        assertEquals("111", d.ndc)
        assertEquals("tablet", d.drugType)
        assertEquals("bucket", d.bucketId)
        assertEquals(100L, d.createdAt)
        assertEquals(50, d.targetCount)
        assertEquals("bc.png", d.barcodeImage)
        assertEquals(30, d.totalPillCount)
        assertTrue(d.isComingFromHL7)
        assertTrue(d.isNdcVerified)
        assertEquals(CountType.FIXED, d.countType)
        assertEquals(TxnPriority.High, d.priority)
        assertTrue(d.isHazardous)
    }

    @Test
    fun equalsHashCode() {
        assertEquals(sample(), sample())
        assertEquals(sample().hashCode(), sample().hashCode())
        assertNotEquals(sample(), sample().copy(txnId = 9L))
    }

    @Test
    fun toString_containsField() {
        assertTrue(sample().toString().contains("txnId=1"))
    }

    @Test
    fun copy() {
        assertEquals(99, sample().copy(totalPillCount = 99).totalPillCount)
    }

    @Test
    fun componentN() {
        val d = sample()
        assertEquals(1L, d.component1())
        assertEquals("Drug", d.component2())
        assertEquals("111", d.component3())
        assertEquals("tablet", d.component4())
        assertEquals("bucket", d.component5())
        assertEquals(100L, d.component6())
        assertEquals(50, d.component7())
        assertEquals("bc.png", d.component8())
        assertEquals(30, d.component9())
        assertEquals(true, d.component10())
        assertEquals(true, d.component11())
        assertEquals(CountType.FIXED, d.component12())
        assertEquals(TxnPriority.High, d.component13())
        assertEquals(true, d.component14())
    }
}
