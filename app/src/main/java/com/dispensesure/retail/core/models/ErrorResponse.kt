package com.dispensesure.retail.core.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Represents a standard error response returned from the API.
 *
 * @property status The HTTP status code of the response.
 * @property isSuccess Indicates whether the API request was successful.
 * @property message The error message returned by the API.
 * @property token Optional token related to the error response, if any.
 * @property data Additional error details returned as a key-value map.
 */
@JsonClass(generateAdapter = true)
data class ErrorResponse(
    @Json(name = "status") val status: Int,
    @Json(name = "is_success") val isSuccess: Boolean,
    @Json(name = "message") val message: String,
    @Json(name = "token") val token: String?,
    @Json(name = "data") val data: Map<String, Any>
)
