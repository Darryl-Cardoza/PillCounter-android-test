package com.dispensesure.retail.core.refreshToken.domain.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Represents the request payload for refreshing an access token.
 *
 * @property refreshToken The refresh token issued during authentication.
 */
@JsonClass(generateAdapter = true)
data class RefreshTokenRequest(
    @Json(name = "refresh_token")
    val refreshToken: String
)
