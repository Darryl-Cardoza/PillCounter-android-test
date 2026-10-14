package com.dispensesure.retail.core.room.models

import com.dispensesure.retail.core.room.models.enums.CountStatus
import com.dispensesure.retail.core.room.models.enums.TxnPriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DispenseTxnEntityTest {

    private fun sample() = DispenseTxnEntity(
        txnId = 1L,
        userLocalId = 2L,
        drugId = 3L,
        targetCount = 50,
        status = CountStatus.COMPLETED,
        note = "note",
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
        val e = DispenseTxnEntity(status = CountStatus.PARTIAL)
        assertEquals(0L, e.txnId)
        assertNull(e.userLocalId)
        assertNull(e.drugId)
        assertNull(e.targetCount)
        assertEquals(CountStatus.PARTIAL, e.status)
        assertNull(e.note)
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
        assertEquals(2L, e.userLocalId)
        assertEquals(3L, e.drugId)
        assertEquals(50, e.targetCount)
        assertEquals(CountStatus.COMPLETED, e.status)
        assertEquals("note", e.note)
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
        assertEquals(CountStatus.PARTIAL, sample().copy(status = CountStatus.PARTIAL).status)
    }

    @Test
    fun componentN() {
        // expiry/lotNo/serialNo/bottleQty/looseQty/batchId were removed in the DB
        // normalization refactor, so the destructuring layout shifted accordingly.
        val e = sample()
        assertEquals(1L, e.component1())        // txnId
        assertEquals(2L, e.component2())        // userLocalId
        assertEquals(3L, e.component3())        // drugId
        assertEquals(50, e.component4())        // targetCount
        assertEquals(CountStatus.COMPLETED, e.component5())
        assertEquals("note", e.component6())
        assertEquals(true, e.component7())      // isSubstitute
        assertEquals("rx1", e.component8())
        assertEquals("rf1", e.component9())
        assertEquals("John", e.component10())   // patientName
        assertEquals(true, e.component11())     // isDeleted
        assertEquals(100L, e.component12())     // createdAt
        assertEquals(200L, e.component13())     // updatedAt
        assertEquals(true, e.component14())     // isComingFromHL7
        assertEquals(true, e.component15())     // isSynced
        assertEquals(true, e.component16())     // isNdcVerified
        assertEquals("bucket", e.component17()) // bucketId
        assertEquals(7L, e.component18())       // substitutedDrugId
        assertEquals("step", e.component19())   // workflowStep
        assertEquals(TxnPriority.High, e.component20())
        assertEquals(true, e.component21())     // isGlovesPresent
        assertEquals(true, e.component22())     // hazardousTrayDetected
    }
}
