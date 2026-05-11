package com.rite.pillcounting.feature.dashboard.domain.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Request body for updating terminal settings.
 */
@JsonClass(generateAdapter = true)
data class TerminalUpdateRequest(
    @Json(name = "terminal_name") val terminalName: String,
    @Json(name = "is_active") val isActive: Boolean
)

