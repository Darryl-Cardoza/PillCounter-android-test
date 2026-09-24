package com.dispensesure.retail.feature.verifyPin.domain.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Represents the nested `data` object inside [VerifyPinResponse].
 */
@JsonClass(generateAdapter = true)
data class VerifyPinData(

    /** Access token for authorization */
    @Json(name = "access_token")
    val accessToken: String? = null,

    /** Refresh token for re-authentication */
    @Json(name = "refresh_token")
    val refreshToken: String? = null,

    /** Token expiration time in seconds */
    @Json(name = "expires_in")
    val expiresIn: Int? = null,

    /** Authenticated user information */
    @Json(name = "user")
    val user: VerifiedUser? = null
)
