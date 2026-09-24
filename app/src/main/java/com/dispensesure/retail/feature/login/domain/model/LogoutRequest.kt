package com.dispensesure.retail.feature.login.domain.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Request body to log out by invalidating the refresh token.
 */
@JsonClass(generateAdapter = true)
data class LogoutRequest(
    @Json(name = "refresh_token")
    val refreshToken: String
)


