package com.rite.pillcounting.feature.history.domain.model

import com.rite.pillcounting.core.room.models.enums.CountStatus
import com.rite.pillcounting.core.room.models.enums.CountType

data class TxnWithDrugDto(
    val txnId: Long,
    val countType: CountType,
    val status: CountStatus,
    val pillCount: Int?,
    val drugName: String?,
    val ndc: String?,
    val barcodeImage: String?,
    val createdAt: Long,
    val targetCount: Int?,
    val note: String?,
    val bucketId: String?,
    val drugType: String?
)
