package com.rite.pillcounting.core.room.models

import com.rite.pillcounting.core.room.models.enums.CountStatus
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.room.models.enums.TxnPriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PillCountTxnEntityTest {

    private fun sample() = PillCountTxnEntity(
        txnId = 1L,
        localId = 2L,
        drugId = 3L,
        countType = CountType.FIXED,
        targetCount = 50,
        status = CountStatus.COMPLETED,
        note = "note",
        expiry = "2027-01",
        lotNo = "lot1",
        barcodeImage = "bc.png",
        isSubstitute = true,
        rxNo = "rx1",
        refillNo = "rf1",
        patientName = "John",
        isDeleted = true,
        createdAt = 100L,
        updatedAt = 200L,
        isComingFromHL7 = true,
        isSynced = true,
        isNdcVerified = true,
        bucketId = "bucket",
        batchId = 4L,
        bottleQty = 5,
        looseQty = 6,
        substitutedDrugId = 7L,
        workflowStep = "step",
        priority = TxnPriority.High,
        isGlovesPresent = true,
        hazardousTrayDetected = true
    )

    @Test
    fun defaultValues() {
        val e = PillCountTxnEntity(countType = CountType.REGULAR, status = CountStatus.PARTIAL)
        assertEquals(0L, e.txnId)
        assertNull(e.localId)
        assertNull(e.drugId)
        assertEquals(CountType.REGULAR, e.countType)
        assertNull(e.targetCount)
        assertEquals(CountStatus.PARTIAL, e.status)
        assertNull(e.note)
        assertNull(e.expiry)
        assertNull(e.lotNo)
        assertNull(e.barcodeImage)
        assertFalse(e.isSubstitute)
        assertNull(e.rxNo)
        assertNull(e.refillNo)
        assertNull(e.patientName)
        assertFalse(e.isDeleted)
        assertTrue(e.createdAt > 0L)
        assertTrue(e.updatedAt > 0L)
        assertNull(e.isComingFromHL7)
        assertNull(e.isSynced)
        assertNull(e.isNdcVerified)
        assertNull(e.bucketId)
        assertNull(e.batchId)
        assertNull(e.bottleQty)
        assertNull(e.looseQty)
        assertNull(e.substitutedDrugId)
        assertNull(e.workflowStep)
        assertNull(e.priority)
        assertFalse(e.isGlovesPresent)
        assertNull(e.hazardousTrayDetected)
    }

    @Test
    fun getters() {
        val e = sample()
        assertEquals(1L, e.txnId)
        assertEquals(2L, e.localId)
        assertEquals(3L, e.drugId)
        assertEquals(CountType.FIXED, e.countType)
        assertEquals(50, e.targetCount)
        assertEquals(CountStatus.COMPLETED, e.status)
        assertEquals("note", e.note)
        assertEquals("2027-01", e.expiry)
        assertEquals("lot1", e.lotNo)
        assertEquals("bc.png", e.barcodeImage)
        assertTrue(e.isSubstitute)
        assertEquals("rx1", e.rxNo)
        assertEquals("rf1", e.refillNo)
        assertEquals("John", e.patientName)
        assertTrue(e.isDeleted)
        assertEquals(100L, e.createdAt)
        assertEquals(200L, e.updatedAt)
        assertEquals(true, e.isComingFromHL7)
        assertEquals(true, e.isSynced)
        assertEquals(true, e.isNdcVerified)
        assertEquals("bucket", e.bucketId)
        assertEquals(4L, e.batchId)
        assertEquals(5, e.bottleQty)
        assertEquals(6, e.looseQty)
        assertEquals(7L, e.substitutedDrugId)
        assertEquals("step", e.workflowStep)
        assertEquals(TxnPriority.High, e.priority)
        assertTrue(e.isGlovesPresent)
        assertEquals(true, e.hazardousTrayDetected)
    }

    @Test
    fun equalsHashCode() {
        assertEquals(sample(), sample())
        assertEquals(sample().hashCode(), sample().hashCode())
        assertNotEquals(sample(), sample().copy(txnId = 99L))
    }

    @Test
    fun toString_containsField() {
        assertTrue(sample().toString().contains("txnId=1"))
    }

    @Test
    fun copy() {
        assertEquals(CountType.REGULAR, sample().copy(countType = CountType.REGULAR).countType)
    }

    @Test
    fun componentN() {
        val e = sample()
        assertEquals(1L, e.component1())
        assertEquals(2L, e.component2())
        assertEquals(3L, e.component3())
        assertEquals(CountType.FIXED, e.component4())
        assertEquals(50, e.component5())
        assertEquals(CountStatus.COMPLETED, e.component6())
        assertEquals("note", e.component7())
        assertEquals("2027-01", e.component8())
        assertEquals("lot1", e.component9())
        assertNull(e.component10())            // serialNo (not set in sample)
        assertEquals("bc.png", e.component11()) // barcodeImage
        assertEquals(true, e.component12())     // isSubstitute
        assertEquals("rx1", e.component13())
        assertEquals("rf1", e.component14())
        assertEquals("John", e.component15())
        assertEquals(true, e.component16())     // isDeleted
        assertEquals(100L, e.component17())     // createdAt
        assertEquals(200L, e.component18())     // updatedAt
        assertEquals(true, e.component19())     // isComingFromHL7
        assertEquals(true, e.component20())     // isSynced
        assertEquals(true, e.component21())     // isNdcVerified
        assertEquals("bucket", e.component22())
        assertEquals(4L, e.component23())       // batchId
        assertEquals(5, e.component24())        // bottleQty
        assertEquals(6, e.component25())        // looseQty
        assertEquals(7L, e.component26())       // substitutedDrugId
        assertEquals("step", e.component27())   // workflowStep
        assertEquals(TxnPriority.High, e.component28())
        assertEquals(true, e.component29())     // isGlovesPresent
        assertEquals(true, e.component30())     // hazardousTrayDetected
    }
}
