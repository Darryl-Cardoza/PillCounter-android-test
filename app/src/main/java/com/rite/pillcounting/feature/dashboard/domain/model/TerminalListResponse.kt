package com.rite.pillcounting.feature.dashboard.domain.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Response from the terminal listing API.
 */
@JsonClass(generateAdapter = true)
data class TerminalListResponse(
    @Json(name = "status") val status: Int? = null,
    @Json(name = "is_success") val isSuccess: Boolean? = null,
    @Json(name = "message") val message: String? = null,
    @Json(name = "token") val token: String? = null,
    @Json(name = "data") val data: TerminalListData? = null
)

@JsonClass(generateAdapter = true)
data class TerminalListData(
    @Json(name = "terminals") val terminals: List<Terminal>? = null
)
