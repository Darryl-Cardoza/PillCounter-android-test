package com.dispensesure.retail.feature.dashboard.domain.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Full response wrapper for the user detail API.
 */
@JsonClass(generateAdapter = true)
data class UserDetailResponse(
    @Json(name = "status") val status: Int?,
    @Json(name = "is_success") val isSuccess: Boolean?,
    @Json(name = "message") val message: String?,
    @Json(name = "token") val token: String?,
    @Json(name = "data") val data: UserDetail?
)
