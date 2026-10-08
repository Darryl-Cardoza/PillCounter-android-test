package com.dispensesure.retail.core.scanning.domain.model

data class GetNdcRequestModel(
    val target_drug: String,
    val scanned_drug: String
)
