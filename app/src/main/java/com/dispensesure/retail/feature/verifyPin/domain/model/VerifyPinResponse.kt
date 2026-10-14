package com.dispensesure.retail.feature.verifyPin.domain.model

import com.dispensesure.retail.core.models.ErrorDetail
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Represents the response from the OTP verification API.
 *
 * Includes metadata (status, message), and on success,
 * contains access tokens, expiration, and user information
 * nested under the `data` object.
 */
@JsonClass(generateAdapter = true)
data class VerifyPinResponse(

    /** HTTP-like status code */
    @Json(name = "status")
    val status: Int? = null,

    /** Whether the request was successful */
    @Json(name = "is_success")
    val isSuccess: Boolean? = null,

    /** Message from the server, e.g., success or error details */
    @Json(name = "message")
    val message: String? = null,

    /** Optional top-level JWT token */
    @Json(name = "token")
    val token: String? = null,

    /** Nested response data (tokens, user info) */
    @Json(name = "data")
    val data: VerifyPinData? = null,

    /** Optional error details if the request failed */
    @Json(name = "detail")
    val detail: List<ErrorDetail>? = null
)


