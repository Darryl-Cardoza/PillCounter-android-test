package com.dispensesure.retail.feature.dashboard.domain.model

import com.dispensesure.retail.core.security.KekInfo
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Represents the full structure of the user details returned from the API.
 */
@JsonClass(generateAdapter = true)
data class UserDetail(
    @Json(name = "profile") val profile: UserProfile?,
    @Json(name = "settings") val settings: UserSettings?,
    @Json(name = "kek") val kek: KekInfo? = null
)



