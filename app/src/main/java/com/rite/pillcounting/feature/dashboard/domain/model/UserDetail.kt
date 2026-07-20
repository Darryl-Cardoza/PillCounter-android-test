package com.rite.pillcounting.feature.dashboard.domain.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Represents the full structure of the user details returned from the API.
 */
@JsonClass(generateAdapter = true)
data class UserDetail(

    @Json(name = "profile") val profile: UserProfile?,
    @Json(name = "settings") val settings: UserSettings?
)



