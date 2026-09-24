package com.dispensesure.retail.feature.history.domain.model

import com.dispensesure.retail.core.room.models.enums.BatchStatus

data class BatchSummary(
    val batchId: Long,
    val createdAt: Long,
    val uniqueNdcCount: Int,
    val status: BatchStatus,
    val bucketId: String? = null,
    val requestIdFromPMS: String? = null
)
