package com.rite.pillcounting.core.scanning.domain.model

import com.rite.pillcounting.core.models.StepState

/**
 * Represents a batch of scanned pills.
 *
 * Each batch stores its unique batch number, the total count of pills detected,
 * and an optional thumbnail image representing the batch.
 *
 * @param batchNumber Identifier for the batch.
 * @param count Total number of pills detected in this batch.
 * @param image A thumbnail image of the batch (nullable if not available).
 */
data class TxnDetail(
    val txnDetailId: Long,
    val batchNumber: Number = 0,
    val count: Int,
    val createdAt: Long,
    val image: String?,
    val type: StepState
)