package com.rite.pillcounting.feature.profile.domain.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ProfileDeleteResponse(
    @Json(name = "status") val status: Int,
    @Json(name = "message") val message: String,
    @Json(name = "is_success") val isSuccess: Boolean
)
