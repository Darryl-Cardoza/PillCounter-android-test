package com.dispensesure.retail.feature.login.domain.model

import com.google.gson.annotations.SerializedName

/**
 * Represents the payload for a login API request.
 *
 * @property email The user's email address or username.
 */
data class LoginRequest(
    @SerializedName("email")
    val email: String,
)
