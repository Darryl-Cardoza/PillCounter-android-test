package com.rite.pillcounting.core.room.models.dtos

data class BatchTxnDto(
    val txnId: Long,
    val drugId: Long?,
    val drugName: String?,
    val ndc: String?,
    val lotNo: String?,
    val expiry: String?,
    val bottleQty: Int?,
    val looseQty: Int?,
    val packageQty: Int?
)