package com.rite.pillcounting.core.room.models.dtos

import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.room.models.enums.TxnPriority

data class PillCountWithDrugAndTotal(
    val txnId: Long,
    val drugName: String?,
    val ndc: String?,
    val drugType: String?,
    val bucketId: String?,
    val createdAt: Long,
    val targetCount: Int?,
    val barcodeImage: String?,
    val totalPillCount: Int,
    val isComingFromHL7 : Boolean,
    val isNdcVerified: Boolean,
    val countType: CountType,
    val priority: TxnPriority?
)