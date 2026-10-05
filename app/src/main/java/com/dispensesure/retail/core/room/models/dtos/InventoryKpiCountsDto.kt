package com.dispensesure.retail.core.room.models.dtos

// In-progress batch counts for the two inventory KPI cards.
data class InventoryKpiCountsDto(
    val cycleCount: Int,
    val pendingBatchCount: Int,
)
