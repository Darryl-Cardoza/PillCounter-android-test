package com.rite.pillcounting.core.room.models.dtos

import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.room.models.enums.CountType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TxnWithDetailsTest {

    private fun detail() = TxnDetailInfo(
        txnId = 1L,
        pillCount = 10,
        imagePath = "img.png",
        type = StepState.SCAN,
        isDeleted = true
    )

    private fun sample() = TxnWithDetails(
        txnId = 1L,
        drugName = "Drug",
        drugId = 2L,
        ndc = "111",
        targetCount = 50,
        expiry = "2027",
        lotNo = "lot",
        note = "note",
        createdAt = 100L,
        barcodeImage = "bc.png",
        totalPillCount = 30,
        countType = CountType.FIXED,
        drugType = "tablet",
        txnDetails = listOf(detail()),
        isComingFromHL7 = true,
        isSubstitute = true,
        requestedDrugName = "ReqDrug",
        requestedNdc = "222",
        workflowStep = "step"
    )

    // ---------- TxnDetailInfo ----------

    @Test
    fun detail_defaults() {
        val d = TxnDetailInfo(txnId = null, pillCount = null, imagePath = null, type = null)
        assertNull(d.txnId)
        assertNull(d.pillCount)
        assertNull(d.imagePath)
        assertNull(d.type)
        assertFalse(d.isDeleted)
    }

    @Test
    fun detail_getters() {
        val d = detail()
        assertEquals(1L, d.txnId)
        assertEquals(10, d.pillCount)
        assertEquals("img.png", d.imagePath)
        assertEquals(StepState.SCAN, d.type)
        assertTrue(d.isDeleted)
    }

    @Test
    fun detail_equalsHashCodeToStringCopyComponents() {
        assertEquals(detail(), detail())
        assertEquals(detail().hashCode(), detail().hashCode())
        assertNotEquals(detail(), detail().copy(pillCount = 99))
        assertTrue(detail().toString().contains("txnId=1"))
        assertEquals(99, detail().copy(pillCount = 99).pillCount)
        val d = detail()
        assertEquals(1L, d.component1())
        assertEquals(10, d.component2())
        assertEquals("img.png", d.component3())
        assertEquals(StepState.SCAN, d.component4())
        assertEquals(true, d.component5())
    }

    // ---------- TxnWithDetails ----------

    @Test
    fun txn_defaults() {
        val t = TxnWithDetails(
            txnId = 1L,
            drugName = null,
            drugId = 2L,
            ndc = null,
            targetCount = null,
            expiry = null,
            lotNo = null,
            note = null,
            createdAt = 0L,
            barcodeImage = null,
            totalPillCount = 0,
            countType = CountType.REGULAR,
            drugType = null,
            txnDetails = emptyList(),
            isComingFromHL7 = false
        )
        assertFalse(t.isSubstitute)
        assertNull(t.requestedDrugName)
        assertNull(t.requestedNdc)
        assertNull(t.workflowStep)
        assertTrue(t.txnDetails.isEmpty())
    }

    @Test
    fun txn_getters() {
        val t = sample()
        assertEquals(1L, t.txnId)
        assertEquals("Drug", t.drugName)
        assertEquals(2L, t.drugId)
        assertEquals("111", t.ndc)
        assertEquals(50, t.targetCount)
        assertEquals("2027", t.expiry)
        assertEquals("lot", t.lotNo)
        assertEquals("note", t.note)
        assertEquals(100L, t.createdAt)
        assertEquals("bc.png", t.barcodeImage)
        assertEquals(30, t.totalPillCount)
        assertEquals(CountType.FIXED, t.countType)
        assertEquals("tablet", t.drugType)
        assertEquals(listOf(detail()), t.txnDetails)
        assertTrue(t.isComingFromHL7)
        assertTrue(t.isSubstitute)
        assertEquals("ReqDrug", t.requestedDrugName)
        assertEquals("222", t.requestedNdc)
        assertEquals("step", t.workflowStep)
    }

    @Test
    fun txn_equalsHashCode() {
        assertEquals(sample(), sample())
        assertEquals(sample().hashCode(), sample().hashCode())
        assertNotEquals(sample(), sample().copy(txnId = 9L))
    }

    @Test
    fun txn_toString_containsField() {
        assertTrue(sample().toString().contains("txnId=1"))
    }

    @Test
    fun txn_copy() {
        assertEquals("z", sample().copy(drugName = "z").drugName)
    }

    @Test
    fun txn_componentN() {
        val t = sample()
        assertEquals(1L, t.component1())
        assertEquals("Drug", t.component2())
        assertEquals(2L, t.component3())
        assertEquals("111", t.component4())
        assertEquals(50, t.component5())
        assertEquals("2027", t.component6())
        assertEquals("lot", t.component7())
        assertEquals("note", t.component8())
        assertEquals(100L, t.component9())
        assertEquals("bc.png", t.component10())
        assertEquals(30, t.component11())
        assertEquals(CountType.FIXED, t.component12())
        assertEquals("tablet", t.component13())
        assertEquals(listOf(detail()), t.component14())
        assertEquals(true, t.component15())
        assertEquals(true, t.component16())
        assertEquals("ReqDrug", t.component17())
        assertEquals("222", t.component18())
        assertEquals("step", t.component19())
    }
}
