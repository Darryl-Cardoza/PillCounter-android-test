package com.dispensesure.retail.feature.history.domain.model

import com.dispensesure.retail.core.room.models.enums.CountStatus

data class TxnWithDrugDto(
    val txnId: Long,
    val isDispense: Boolean,
    val status: CountStatus,
    val pillCount: Int?,
    val drugName: String?,
    val ndc: String?,
    val bottleInfoListJson: String?,
    val createdAt: Long,
    val targetCount: Int?,
    val note: String?,
    val bucketId: String?,
    val drugType: String?,
    val strength: String? = null,
    val dosageForm: String? = null,
    /** Absolute local path to the downloaded drug image (.webp). Null if not yet downloaded. */
    val drugImagePath: String? = null,
    val rxNo: String? = null,
    val refillNo: String? = null,
)
