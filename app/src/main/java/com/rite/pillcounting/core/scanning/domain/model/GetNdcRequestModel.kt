package com.rite.pillcounting.core.scanning.domain.model

data class GetNdcRequestModel(
    val target_ndc: String,
    val scanned_ndc: String
)