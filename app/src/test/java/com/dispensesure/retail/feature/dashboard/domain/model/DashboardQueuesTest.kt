package com.dispensesure.retail.feature.dashboard.domain.model

import com.dispensesure.retail.core.room.models.dtos.BatchSummaryDto
import com.dispensesure.retail.core.room.models.dtos.InventoryKpiCountsDto
import com.dispensesure.retail.core.room.models.dtos.PillCountWithDrugAndTotal
import com.dispensesure.retail.core.room.models.enums.BatchStatus
import com.dispensesure.retail.core.room.models.enums.CountStatus
import com.dispensesure.retail.core.room.models.enums.TxnPriority
import com.dispensesure.retail.feature.history.domain.model.TxnWithDrugDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardQueuesTest {

    private fun txn(
        txnId: Long,
        createdAt: Long = txnId,
        drugType: String? = null,
        priority: TxnPriority? = null,
        isHazardous: Boolean = false,
    ) = PillCountWithDrugAndTotal(
        txnId = txnId,
        drugName = "Drug$txnId",
        ndc = "ndc$txnId",
        drugType = drugType,
        bucketId = null,
        createdAt = createdAt,
        targetCount = 10,
        bottleInfoListJson = null,
        totalPillCount = 0,
        isComingFromHL7 = false,
        isNdcVerified = false,
        isDispense = true,
        priority = priority,
        isHazardous = isHazardous,
    )

    private fun batch(
        batchId: Long,
        createdAt: Long = batchId,
        status: BatchStatus = BatchStatus.INPROGRESS,
        requestIdFromPMS: String? = null,
    ) = BatchSummaryDto(
        batchId = batchId,
        createdAt = createdAt,
        uniqueNdcCount = 1,
        status = status.name,
        bucketId = null,
        requestIdFromPMS = requestIdFromPMS,
    )

    private fun completedTxn(txnId: Long, createdAt: Long) = TxnWithDrugDto(
        txnId = txnId,
        isDispense = true,
        status = CountStatus.COMPLETED,
        pillCount = 7,
        drugName = "Drug$txnId",
        ndc = "ndc$txnId",
        bottleInfoListJson = null,
        createdAt = createdAt,
        targetCount = 10,
        note = null,
        bucketId = null,
        drugType = null,
        strength = "35 mg/1",
        dosageForm = "CAPSULE",
        drugImagePath = "/img/$txnId.webp",
    )

    // High + controlled (1), hazardous (2), plain (3).
    private val dispense = listOf(
        txn(1, priority = TxnPriority.High, drugType = "CII"),
        txn(2, isHazardous = true),
        txn(3),
    ).toPendingDispenseItems()

    // PMS cycle count (10), manual (11).
    private val inventory = listOf(batch(10, requestIdFromPMS = "pms"), batch(11)).toPendingInventoryItems()

    private val recent = recentActivityItems(
        dispenses = listOf(completedTxn(20, createdAt = 5)),
        batches = listOf(batch(30, createdAt = 6, status = BatchStatus.COMPLETED)),
    )

    private val sources = QueueSources(
        pendingDispense = dispense,
        pendingInventory = inventory,
        recentActivity = recent,
        inventoryKpiCounts = InventoryKpiCountsDto(cycleCount = 4, pendingBatchCount = 7),
    )

    private fun List<QueueItem.Dispense>.txnIds() = map { it.txn.txnId }
    private fun List<QueueItem.Inventory>.batchIds() = map { it.batch.batchId }

    // ── filtering ──

    @Test
    fun `no filter passes every list through`() {
        val queues = buildDashboardQueues(sources, activeFilter = null)

        assertSame(dispense, queues.dispenseQueue)
        assertSame(inventory, queues.inventoryQueue)
        assertSame(recent, queues.recentActivity)
    }

    @Test
    fun `dispense filters narrow only the dispense queue`() {
        mapOf(
            KpiFilter.DISP_HIGH_PRIORITY to listOf(1L),
            KpiFilter.DISP_CONTROLLED to listOf(1L),
            KpiFilter.DISP_HAZARDOUS to listOf(2L),
            KpiFilter.DISP_PENDING to listOf(1L, 2L, 3L),
        ).forEach { (filter, expected) ->
            val queues = buildDashboardQueues(sources, filter)
            assertEquals(filter.name, expected, queues.dispenseQueue.txnIds())
            assertSame(filter.name, inventory, queues.inventoryQueue)
        }
    }

    @Test
    fun `inventory filters narrow only the inventory queue`() {
        mapOf(
            KpiFilter.INV_CYCLE_COUNT to listOf(10L),
            KpiFilter.INV_PENDING_BATCH to listOf(11L),
        ).forEach { (filter, expected) ->
            val queues = buildDashboardQueues(sources, filter)
            assertEquals(filter.name, expected, queues.inventoryQueue.batchIds())
            assertSame(filter.name, dispense, queues.dispenseQueue)
        }
    }

    @Test
    fun `recent activity is never filtered`() {
        KpiFilter.entries.forEach { filter ->
            assertSame(filter.name, recent, buildDashboardQueues(sources, filter).recentActivity)
        }
    }

    // ── counts ──

    @Test
    fun `kpi counts use unfiltered dispense rows and the inventory count query`() {
        val counts = buildDashboardQueues(sources, KpiFilter.DISP_HAZARDOUS).kpiCounts

        assertEquals(1, counts[KpiFilter.DISP_HIGH_PRIORITY])
        assertEquals(3, counts[KpiFilter.DISP_PENDING])
        assertEquals(1, counts[KpiFilter.DISP_CONTROLLED])
        assertEquals(1, counts[KpiFilter.DISP_HAZARDOUS])
        assertEquals(4, counts[KpiFilter.INV_CYCLE_COUNT])
        assertEquals(7, counts[KpiFilter.INV_PENDING_BATCH])
    }

    @Test
    fun `tab counts show queue totals without a filter`() {
        val queues = buildDashboardQueues(sources, activeFilter = null)

        assertEquals(3, queues.dispenseTabCount)
        assertEquals(11, queues.inventoryTabCount)
    }

    @Test
    fun `tab counts follow the active filter on its own queue only`() {
        val dispenseFiltered = buildDashboardQueues(sources, KpiFilter.DISP_HAZARDOUS)
        assertEquals(1, dispenseFiltered.dispenseTabCount)
        assertEquals(11, dispenseFiltered.inventoryTabCount)

        val inventoryFiltered = buildDashboardQueues(sources, KpiFilter.INV_CYCLE_COUNT)
        assertEquals(3, inventoryFiltered.dispenseTabCount)
        assertEquals(4, inventoryFiltered.inventoryTabCount)
    }

    // ── mappers / order ──

    @Test
    fun `pending dispense keeps the dao order`() {
        val items = listOf(txn(2, createdAt = 9), txn(1, createdAt = 1)).toPendingDispenseItems()
        assertEquals(listOf(2L, 1L), items.txnIds())
    }

    @Test
    fun `pending dispense maps priority controlled and hazardous flags`() {
        val item = listOf(txn(1, priority = TxnPriority.High, drugType = "CII", isHazardous = true))
            .toPendingDispenseItems().single()
        assertTrue(item.isHighPriority)
        assertTrue(item.isControlled)
        assertTrue(item.isHazardous)

        val plain = listOf(txn(2)).toPendingDispenseItems().single()
        assertFalse(plain.isHighPriority)
        assertFalse(plain.isControlled)
        assertFalse(plain.isHazardous)
    }

    @Test
    fun `pending inventory is sorted oldest first`() {
        val items = listOf(batch(1, createdAt = 9), batch(2, createdAt = 3)).toPendingInventoryItems()
        assertEquals(listOf(2L, 1L), items.batchIds())
    }

    @Test
    fun `recent activity keeps completed batches only and sorts newest first`() {
        val items = recentActivityItems(
            dispenses = listOf(completedTxn(20, createdAt = 5)),
            batches = listOf(
                batch(30, createdAt = 6, status = BatchStatus.COMPLETED),
                batch(31, createdAt = 7, status = BatchStatus.INPROGRESS),
            ),
        )

        assertEquals(listOf(6L, 5L), items.map { it.createdAt })
        val row = items.filterIsInstance<QueueItem.Dispense>().single()
        assertEquals("/img/20.webp", row.txn.drugImagePath)
        assertEquals("35 mg/1", row.txn.strength)
        assertEquals("CAPSULE", row.txn.dosageForm)
        assertEquals(7, row.txn.totalPillCount)
    }
}
