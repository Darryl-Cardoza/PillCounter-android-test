package com.rite.pillcounting.core.room.models.dtos

data class BatchSummaryDto(
    val batchId: Long,
    val createdAt: Long,
    val uniqueNdcCount: Int,
    val status: String,
    val bucketId: String?,
    val requestIdFromPMS: String?
)
