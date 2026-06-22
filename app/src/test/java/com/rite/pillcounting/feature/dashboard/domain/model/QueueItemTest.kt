package com.rite.pillcounting.feature.dashboard.domain.model

import com.rite.pillcounting.core.room.models.dtos.BatchSummaryDto
import com.rite.pillcounting.core.room.models.dtos.PillCountWithDrugAndTotal
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.room.models.enums.TxnPriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueItemTest {

    private fun txn(
        txnId: Long = 1L,
        bucketId: String? = "bucket-1",
        createdAt: Long = 1000L,
    ) = PillCountWithDrugAndTotal(
        txnId = txnId,
        drugName = "Aspirin",
        ndc = "12345-678-90",
        drugType = "OTC",
        bucketId = bucketId,
        createdAt = createdAt,
        targetCount = 30,
        barcodeImage = "img",
        totalPillCount = 10,
        isComingFromHL7 = false,
        isNdcVerified = true,
        countType = CountType.FIXED,
        priority = TxnPriority.High,
        isHazardous = false,
    )

    private fun batch(
        batchId: Long = 5L,
        createdAt: Long = 2000L,
        bucketId: String? = "bucket-2",
        requestIdFromPMS: String? = "req-1",
    ) = BatchSummaryDto(
        batchId = batchId,
        createdAt = createdAt,
        uniqueNdcCount = 3,
        status = "INPROGRESS",
        bucketId = bucketId,
        requestIdFromPMS = requestIdFromPMS,
    )

    // ---------- Dispense ----------

    @Test
    fun dispense_exposesUnderlyingTxnAndComputedProps() {
        val t = txn()
        val item = QueueItem.Dispense(
            txn = t,
            isHazardous = true,
            isHighPriority = false,
            isControlled = true,
        )

        assertSame(t, item.txn)
        assertTrue(item.isHazardous)
        assertFalse(item.isHighPriority)
        assertTrue(item.isControlled)
        assertEquals(t.createdAt, item.createdAt)
        assertEquals(t.bucketId, item.bucketId)
    }

    @Test
    fun dispense_is340B_trueWhenBucketIdNonBlank() {
        val item = QueueItem.Dispense(txn(bucketId = "abc"), false, false, false)
        assertTrue(item.is340B)
    }

    @Test
    fun dispense_is340B_falseWhenBucketIdBlank() {
        val item = QueueItem.Dispense(txn(bucketId = "   "), false, false, false)
        assertFalse(item.is340B)
    }

    @Test
    fun dispense_is340B_falseWhenBucketIdNull() {
        val item = QueueItem.Dispense(txn(bucketId = null), false, false, false)
        assertFalse(item.is340B)
        assertNull(item.bucketId)
    }

    @Test
    fun dispense_dataClassMembers() {
        val t = txn()
        val a = QueueItem.Dispense(t, true, true, true)
        val b = QueueItem.Dispense(t, true, true, true)
        val c = a.copy(isControlled = false)

        // equals / hashCode
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)

        // toString
        assertTrue(a.toString().contains("Dispense"))

        // componentN
        assertSame(t, a.component1())
        assertTrue(a.component2())
        assertTrue(a.component3())
        assertTrue(a.component4())

        // copy
        assertFalse(c.isControlled)
        assertEquals(a.txn, c.txn)
    }

    // ---------- Inventory ----------

    @Test
    fun inventory_exposesUnderlyingBatchAndComputedProps() {
        val b = batch()
        val item = QueueItem.Inventory(batch = b)

        assertSame(b, item.batch)
        assertEquals(b.createdAt, item.createdAt)
        assertEquals(b.bucketId, item.bucketId)
    }

    @Test
    fun inventory_is340B_trueAndFalse() {
        assertTrue(QueueItem.Inventory(batch(bucketId = "x")).is340B)
        assertFalse(QueueItem.Inventory(batch(bucketId = "  ")).is340B)
        assertFalse(QueueItem.Inventory(batch(bucketId = null)).is340B)
    }

    @Test
    fun inventory_isCycleCount_trueWhenRequestIdNonBlank() {
        assertTrue(QueueItem.Inventory(batch(requestIdFromPMS = "req")).isCycleCount)
    }

    @Test
    fun inventory_isCycleCount_falseWhenRequestIdBlank() {
        assertFalse(QueueItem.Inventory(batch(requestIdFromPMS = "   ")).isCycleCount)
    }

    @Test
    fun inventory_isCycleCount_falseWhenRequestIdNull() {
        assertFalse(QueueItem.Inventory(batch(requestIdFromPMS = null)).isCycleCount)
    }

    @Test
    fun inventory_dataClassMembers() {
        val b = batch()
        val a = QueueItem.Inventory(b)
        val a2 = QueueItem.Inventory(b)
        val c = a.copy(batch = batch(batchId = 99L))

        assertEquals(a, a2)
        assertEquals(a.hashCode(), a2.hashCode())
        assertNotEquals(a, c)
        assertTrue(a.toString().contains("Inventory"))
        assertSame(b, a.component1())
        assertEquals(99L, c.batch.batchId)
    }

    @Test
    fun queueItem_isSealedHierarchy() {
        val items: List<QueueItem> = listOf(
            QueueItem.Dispense(txn(), false, false, false),
            QueueItem.Inventory(batch()),
        )
        assertEquals(2, items.size)
    }
}
