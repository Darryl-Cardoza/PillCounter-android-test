package com.rite.pillcounting.feature.dispenseFlow.domain.model

data class GetNdcRequestModel(
    val target_ndc: String,
    val scanned_ndc: String
)