package com.rite.pillcounting.feature.dashboard.domain.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Response from terminal update API.
 */
@JsonClass(generateAdapter = true)
data class TerminalUpdateResponse(
    @Json(name = "message") val message: String? = null,
    @Json(name = "success") val success: Boolean? = null,
    @Json(name = "data") val data: Terminal? = null
)

