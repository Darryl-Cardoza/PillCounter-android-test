package com.rite.pillcounting.feature.batchCount.domain.model

data class BatchLotEntry(
    val lotNo: String?,
    val expiry: String?,
    val count: Int
)

data class BatchDrugGroup(
    val drugId: Long?,
    val drugName: String,
    val ndc: String,
    val sealedTotal: Int,
    val openedTotal: Int,
    val totalCount: Int,
    val sealedLots: List<BatchLotEntry>,
    val openedLots: List<BatchLotEntry>
)