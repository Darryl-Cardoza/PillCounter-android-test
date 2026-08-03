package com.rite.pillcounting.feature.dashboard.domain.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Represents the KEK (Key Encryption Key) issued by the server.
 */
@JsonClass(generateAdapter = true)
data class KekInfo(
    @Json(name = "key_id") val keyId: String,
    @Json(name = "version") val version: Int,
    @Json(name = "algorithm") val algorithm: String,
    @Json(name = "key_material") val keyMaterial: String,
)