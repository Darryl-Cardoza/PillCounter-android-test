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
        // expiry/lotNo/serialNo/bottleQty/looseQty/batchId were removed in the DB
        // normalization refactor, so the destructuring layout shifted accordingly.
        val e = sample()
        assertEquals(1L, e.component1())        // txnId
        assertEquals(2L, e.component2())        // localId
        assertEquals(3L, e.component3())        // drugId
        assertEquals(CountType.FIXED, e.component4())
        assertEquals(50, e.component5())        // targetCount
        assertEquals(CountStatus.COMPLETED, e.component6())
        assertEquals("note", e.component7())
        assertEquals("bc.png", e.component8())  // barcodeImage
        assertEquals(true, e.component9())      // isSubstitute
        assertEquals("rx1", e.component10())
        assertEquals("rf1", e.component11())
        assertEquals("John", e.component12())   // patientName
        assertEquals(true, e.component13())     // isDeleted
        assertEquals(100L, e.component14())     // createdAt
        assertEquals(200L, e.component15())     // updatedAt
        assertEquals(true, e.component16())     // isComingFromHL7
        assertEquals(true, e.component17())     // isSynced
        assertEquals(true, e.component18())     // isNdcVerified
        assertEquals("bucket", e.component19()) // bucketId
        assertEquals(7L, e.component20())       // substitutedDrugId
        assertEquals("step", e.component21())   // workflowStep
        assertEquals(TxnPriority.High, e.component22())
        assertEquals(true, e.component23())     // isGlovesPresent
        assertEquals(true, e.component24())     // hazardousTrayDetected
    }
}
