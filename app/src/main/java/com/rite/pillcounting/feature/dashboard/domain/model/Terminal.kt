package com.rite.pillcounting.feature.dashboard.domain.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Represents a terminal/workstation associated with a user.
 */
@JsonClass(generateAdapter = true)
data class Terminal(
    @Json(name = "terminal_id") val terminalId: String? = null,
    @Json(name = "terminal_name") val terminalName: String? = null,
    @Json(name = "is_active") val isActive: Boolean? = null,
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "updated_at") val updatedAt: String? = null
)

