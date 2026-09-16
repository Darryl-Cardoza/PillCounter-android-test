package com.rite.pillcounting.core.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * A generic wrapper for all API responses.
 * @param T The type of the data payload.
 */
@JsonClass(generateAdapter = true)
data class ApiResponse<T>(
    @Json(name = "status") val status: Int,
    @Json(name = "is_success") val isSuccess: Boolean,
    @Json(name = "message") val message: String,
    @Json(name = "token") val token: String?,
    @Json(name = "data") val data: T?
)
