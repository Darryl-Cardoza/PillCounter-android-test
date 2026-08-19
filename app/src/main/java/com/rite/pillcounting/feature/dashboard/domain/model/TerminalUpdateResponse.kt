package com.rite.pillcounting.feature.dashboard.domain.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Response from terminal update API.
 */
@JsonClass(generateAdapter = true)
data class TerminalUpdateResponse(
    @Json(name = "status") val status: Int? = null,
    @Json(name = "is_success") val isSuccess: Boolean? = null,
    @Json(name = "message") val message: String? = null,
    @Json(name = "token") val token: String? = null,
    @Json(name = "data") val data: TerminalUpdateData? = null
)

@JsonClass(generateAdapter = true)
data class TerminalUpdateData(
    @Json(name = "terminal") val terminal: Terminal? = null
)
