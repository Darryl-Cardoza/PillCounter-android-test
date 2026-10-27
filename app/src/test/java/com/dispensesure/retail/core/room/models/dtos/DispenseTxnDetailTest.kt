package com.dispensesure.retail.core.room.models.dtos

import com.dispensesure.retail.core.models.StepState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DispenseTxnDetailTest {

    private fun detail() = DispenseCountInfo(
        countId = 5L,
        txnId = 1L,
        pillCount = 10,
        imagePath = "img.png",
        step = StepState.SCAN
    )

    private fun sample() = DispenseTxnDetail(
        txnId = 1L,
        drugName = "Drug",
        drugId = 2L,
        ndc = "111",
        targetCount = 50,
        note = "note",
        createdAt = 100L,
        bottlesJson = "bc.png",
        totalPillCount = 30,
        drugType = "tablet",
        counts = listOf(detail()),
        isComingFromHL7 = true,
        isSubstitute = true,
        requestedDrugName = "ReqDrug",
        requestedNdc = "222",
        workflowStep = "step"
    )

    // ---------- DispenseCountInfo ----------

    @Test
    fun detail_defaults() {
        val d = DispenseCountInfo(txnId = null, pillCount = null, imagePath = null, step = null)
        assertNull(d.txnId)
        assertNull(d.pillCount)
        assertNull(d.imagePath)
        assertNull(d.step)
    }

    @Test
    fun detail_getters() {
        val d = detail()
        assertEquals(5L, d.countId)
        assertEquals(1L, d.txnId)
        assertEquals(10, d.pillCount)
        assertEquals("img.png", d.imagePath)
        assertEquals(StepState.SCAN, d.step)
    }

    @Test
    fun detail_equalsHashCodeToStringCopyComponents() {
        assertEquals(detail(), detail())
        assertEquals(detail().hashCode(), detail().hashCode())
        assertNotEquals(detail(), detail().copy(pillCount = 99))
        assertTrue(detail().toString().contains("txnId=1"))
        assertEquals(99, detail().copy(pillCount = 99).pillCount)
        val d = detail()
        assertEquals(5L, d.component1())
        assertEquals(1L, d.component2())
        assertEquals(10, d.component3())
        assertEquals("img.png", d.component4())
        assertEquals(StepState.SCAN, d.component5())
    }

    // ---------- DispenseTxnDetail ----------

    @Test
    fun txn_defaults() {
        val t = DispenseTxnDetail(
            txnId = 1L,
            drugName = null,
            drugId = 2L,
            ndc = null,
            targetCount = null,
            note = null,
            createdAt = 0L,
            bottlesJson = null,
            totalPillCount = 0,
            drugType = null,
            counts = emptyList(),
            isComingFromHL7 = false
        )
        assertFalse(t.isSubstitute)
        assertNull(t.requestedDrugName)
        assertNull(t.requestedNdc)
        assertNull(t.workflowStep)
        assertTrue(t.counts.isEmpty())
    }

    @Test
    fun txn_getters() {
        val t = sample()
        assertEquals(1L, t.txnId)
        assertEquals("Drug", t.drugName)
        assertEquals(2L, t.drugId)
        assertEquals("111", t.ndc)
        assertEquals(50, t.targetCount)
        assertEquals("note", t.note)
        assertEquals(100L, t.createdAt)
        assertEquals("bc.png", t.bottlesJson)
        assertEquals(30, t.totalPillCount)
        assertEquals("tablet", t.drugType)
        assertEquals(listOf(detail()), t.counts)
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
        // expiry/lotNo were removed; strength/dosageForm/bucketId were added (all null
        // in sample()), shifting the destructuring layout.
        val t = sample()
        assertEquals(1L, t.component1())        // txnId
        assertEquals("Drug", t.component2())    // drugName
        assertEquals(2L, t.component3())        // drugId
        assertEquals("111", t.component4())     // ndc
        assertEquals(50, t.component5())        // targetCount
        assertEquals("note", t.component6())    // note
        assertEquals(100L, t.component7())      // createdAt
        assertEquals("bc.png", t.component8())  // bottlesJson
        assertEquals(30, t.component9())        // totalPillCount
        assertEquals("tablet", t.component10()) // drugType
        assertNull(t.component11())             // strength
        assertNull(t.component12())             // dosageForm
        assertNull(t.component13())             // bucketId
        assertEquals(listOf(detail()), t.component14()) // counts
        assertEquals(true, t.component15())     // isComingFromHL7
        assertEquals(true, t.component16())     // isSubstitute
        assertEquals("ReqDrug", t.component17()) // requestedDrugName
        assertEquals("222", t.component18())    // requestedNdc
        assertEquals("step", t.component19())   // workflowStep
    }
}
