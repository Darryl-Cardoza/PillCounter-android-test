package com.rite.pillcounting.feature.history.domain.model

data class BatchSummary(
    val batchId: Long,
    val createdAt: Long,
    val itemCount: Int
)
