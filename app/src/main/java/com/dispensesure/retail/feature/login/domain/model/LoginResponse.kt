package com.dispensesure.retail.feature.login.domain.model

import com.dispensesure.retail.core.models.ErrorDetail
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Represents the data structure for a login API response using Moshi.
 *
 * It can represent:
 * - Success with user registered / OTP sent
 * - Success with login complete
 * - Failure with detailed error info
 */
@JsonClass(generateAdapter = true)
data class LoginResponse(

    // Common fields
    @Json(name = "status")
    val status: Int? = null,

    @Json(name = "is_success")
    val isSuccess: Boolean? = null,

    @Json(name = "message")
    val message: String? = null,

    // Optional token (may be null)
    @Json(name = "token")
    val token: String? = null,

    // Extra data object (could be empty or contain user info later)
    @Json(name = "data")
    val data: Any? = null,

    // Error details
    @Json(name = "detail")
    val detail: List<ErrorDetail>? = null
)
