package com.rite.pillcounting.feature.dashboard.domain.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class UserRole(
    @Json(name = "_id") val id: String? = null,
    @Json(name = "name") val name: String? = null
)
