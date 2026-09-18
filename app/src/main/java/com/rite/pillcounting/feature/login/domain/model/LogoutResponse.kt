package com.rite.pillcounting.feature.login.domain.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Response model for logout API call.
 */
@JsonClass(generateAdapter = true)
data class LogoutResponse(
    @Json(name = "status")
    val status: Int? = null,

    @Json(name = "is_success")
    val isSuccess: Boolean? = null,

    @Json(name = "message")
    val message: String? = null
)
