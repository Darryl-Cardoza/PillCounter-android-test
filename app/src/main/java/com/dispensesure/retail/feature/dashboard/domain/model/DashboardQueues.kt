package com.dispensesure.retail.feature.dashboard.domain.model

import com.dispensesure.retail.core.models.isControlledDrugType
import com.dispensesure.retail.core.room.models.dtos.BatchSummaryDto
import com.dispensesure.retail.core.room.models.dtos.InventoryKpiCountsDto
import com.dispensesure.retail.core.room.models.dtos.PillCountWithDrugAndTotal
import com.dispensesure.retail.core.room.models.enums.BatchStatus
import com.dispensesure.retail.core.room.models.enums.TxnPriority
import com.dispensesure.retail.feature.history.domain.model.TxnWithDrugDto

// Mapped Room lists per tab, before any KPI filter.
internal data class QueueSources(
    val pendingDispense: List<QueueItem.Dispense> = emptyList(),
    val pendingInventory: List<QueueItem.Inventory> = emptyList(),
    val recentActivity: List<QueueItem> = emptyList(),
    val inventoryKpiCounts: InventoryKpiCountsDto = InventoryKpiCountsDto(0, 0),
    // Tabs whose collector has started, and tabs whose first list has arrived.
    val observedTabs: Set<DashboardTab> = emptySet(),
    val loadedTabs: Set<DashboardTab> = emptySet(),
)

// What each tab shows, the KPI card counts, and the counts in the tab headers.
internal data class DashboardQueues(
    val dispenseQueue: List<QueueItem.Dispense>,
    val inventoryQueue: List<QueueItem.Inventory>,
    val recentActivity: List<QueueItem>,
    val kpiCounts: Map<KpiFilter, Int>,
    val dispenseTabCount: Int,
    val inventoryTabCount: Int,
    val loadedTabs: Set<DashboardTab>,
    val loadingTabs: Set<DashboardTab>,
)

// The one place the dashboard lists and KPI counts are derived.
// A filter narrows only its own queue; Recent Activity is never filtered.
internal fun buildDashboardQueues(sources: QueueSources, activeFilter: KpiFilter?): DashboardQueues {
    val dispense = sources.pendingDispense
    val inventory = sources.pendingInventory
    val dispenseFilter = activeFilter?.takeIf { it.queue == DashboardTab.DISPENSE_QUEUE }
    val inventoryFilter = activeFilter?.takeIf { it.queue == DashboardTab.INVENTORY_QUEUE }
    val inventoryCounts = sources.inventoryKpiCounts
    // Dispense filters are counted from the list; inventory counts come from the count query.
    val kpiCounts = KpiFilter.entries
        .filter { it.queue == DashboardTab.DISPENSE_QUEUE }
        .associateWith { filter -> dispense.count { it.matches(filter) } } +
        mapOf(
            KpiFilter.INV_CYCLE_COUNT to inventoryCounts.cycleCount,
            KpiFilter.INV_PENDING_BATCH to inventoryCounts.pendingBatchCount,
        )
    return DashboardQueues(
        dispenseQueue = dispenseFilter?.let { filter -> dispense.filter { it.matches(filter) } } ?: dispense,
        inventoryQueue = inventoryFilter?.let { filter -> inventory.filter { it.matches(filter) } } ?: inventory,
        recentActivity = sources.recentActivity,
        kpiCounts = kpiCounts,
        // Header count follows the active filter's card; inventory total comes from the count query.
        dispenseTabCount = dispenseFilter?.let { kpiCounts.getValue(it) } ?: dispense.size,
        inventoryTabCount = inventoryFilter?.let { kpiCounts.getValue(it) }
            ?: (inventoryCounts.cycleCount + inventoryCounts.pendingBatchCount),
        loadedTabs = sources.loadedTabs,
        loadingTabs = sources.observedTabs - sources.loadedTabs,
    )
}

private fun QueueItem.Dispense.matches(filter: KpiFilter): Boolean = when (filter) {
    KpiFilter.DISP_PENDING -> true
    KpiFilter.DISP_HIGH_PRIORITY -> isHighPriority
    KpiFilter.DISP_CONTROLLED -> isControlled
    KpiFilter.DISP_HAZARDOUS -> isHazardous
    KpiFilter.INV_CYCLE_COUNT, KpiFilter.INV_PENDING_BATCH -> false
}

private fun QueueItem.Inventory.matches(filter: KpiFilter): Boolean = when (filter) {
    KpiFilter.INV_CYCLE_COUNT -> isCycleCount
    KpiFilter.INV_PENDING_BATCH -> !isCycleCount
    KpiFilter.DISP_HIGH_PRIORITY, KpiFilter.DISP_PENDING,
    KpiFilter.DISP_CONTROLLED, KpiFilter.DISP_HAZARDOUS -> false
}

// Keeps the DAO order: priority, then oldest first.
internal fun List<PillCountWithDrugAndTotal>.toPendingDispenseItems(): List<QueueItem.Dispense> =
    map { txn ->
        QueueItem.Dispense(
            txn = txn,
            isHazardous = txn.isHazardous,
            isHighPriority = txn.priority == TxnPriority.High,
            isControlled = isControlledDrugType(txn.drugType),
        )
    }

// Oldest batch first.
internal fun List<BatchSummaryDto>.toPendingInventoryItems(): List<QueueItem.Inventory> =
    sortedBy { it.createdAt }.map { QueueItem.Inventory(batch = it) }

// Completed dispenses and completed batches, newest first.
internal fun recentActivityItems(
    dispenses: List<TxnWithDrugDto>,
    batches: List<BatchSummaryDto>,
): List<QueueItem> {
    val dispenseItems = dispenses.map { completedTxn ->
        QueueItem.Dispense(
            txn = PillCountWithDrugAndTotal(
                txnId = completedTxn.txnId,
                drugName = completedTxn.drugName,
                ndc = completedTxn.ndc,
                drugType = completedTxn.drugType,
                bucketId = completedTxn.bucketId,
                createdAt = completedTxn.createdAt,
                targetCount = completedTxn.targetCount,
                bottleInfoListJson = completedTxn.bottleInfoListJson,
                totalPillCount = completedTxn.pillCount ?: 0,
                isComingFromHL7 = false,
                isNdcVerified = false,
                isDispense = completedTxn.isDispense,
                priority = null,
                strength = completedTxn.strength,
                dosageForm = completedTxn.dosageForm,
                drugImagePath = completedTxn.drugImagePath,
                rxNo = completedTxn.rxNo,
                refillNo = completedTxn.refillNo,
            ),
            isHazardous = false,
            isHighPriority = false,
            isControlled = isControlledDrugType(completedTxn.drugType),
        )
    }
    val inventoryItems = batches
        .filter { it.status == BatchStatus.COMPLETED.name }
        .map { QueueItem.Inventory(batch = it) }
    return (dispenseItems + inventoryItems).sortedByDescending { it.createdAt }
}
