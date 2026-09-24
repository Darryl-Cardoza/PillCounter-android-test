package com.dispensesure.retail.core.refreshToken.domain.model

import com.dispensesure.retail.core.models.ErrorDetail
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Represents the response from the refresh token API.
 * Handles both success and error cases in a unified model.
 */
@JsonClass(generateAdapter = true)
data class RefreshTokenResponse(

    @Json(name = "status")
    val status: Int? = null,

    @Json(name = "is_success")
    val isSuccess: Boolean? = null,

    @Json(name = "message")
    val message: String? = null,

    @Json(name = "access_token")
    val accessToken: String? = null,

    @Json(name = "refresh_token")
    val refreshToken: String? = null,

    @Json(name = "expires_in")
    val expiresIn: Int? = null,

    @Json(name = "token")
    val token: String? = null,

    @Json(name = "data")
    val data: Any? = null,

    @Json(name = "detail")
    val detail: List<ErrorDetail>? = null
)
