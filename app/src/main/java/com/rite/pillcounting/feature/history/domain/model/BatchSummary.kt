package com.rite.pillcounting.feature.history.domain.model

import com.rite.pillcounting.core.room.models.enums.BatchStatus

data class BatchSummary(
    val batchId: Long,
    val createdAt: Long,
    val uniqueNdcCount: Int,
    val status: BatchStatus,
    val bucketId: String? = null,
    val requestIdFromPMS: String? = null
)
