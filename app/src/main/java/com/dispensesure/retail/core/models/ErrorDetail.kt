package com.dispensesure.retail.core.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Represents a detailed error object returned from the backend API.
 *
 * Typically used in structured error responses, especially for form validation,
 * parsing issues, or other granular API failures.
 *
 * @property loc A list representing the location or path in the request payload where the error occurred.
 * Often includes keys or field names (e.g., ["body", "username"]).
 * @property msg A human-readable error message describing what went wrong.
 * @property type The type or category of error (e.g., "value_error", "type_error").
 */
@JsonClass(generateAdapter = true)
data class ErrorDetail(
    @Json(name = "loc")
    val loc: List<Any>? = null,

    @Json(name = "msg")
    val msg: String? = null,

    @Json(name = "type")
    val type: String? = null
)
