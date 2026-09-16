package com.rite.pillcounting.core.room.models.dtos

import com.rite.pillcounting.core.room.models.enums.TxnPriority

data class PillCountWithDrugAndTotal(
    val txnId: Long,
    val drugName: String?,
    val ndc: String?,
    val drugType: String?,
    val bucketId: String?,
    val createdAt: Long,
    val targetCount: Int?,
    val bottleInfoListJson: String?,
    val totalPillCount: Int,
    val isComingFromHL7 : Boolean,
    val isNdcVerified: Boolean,
    val isDispense: Boolean,
    val priority: TxnPriority?,
    val isHazardous: Boolean = false,
    val strength: String? = null,
    val dosageForm: String? = null,
    /** Absolute local path to the downloaded drug image (.webp). Null if not yet downloaded. */
    val drugImagePath: String? = null,
)
