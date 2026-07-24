package com.rite.pillcounting.feature.profile.domain.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Represents a state/province selectable in the profile screen, scoped to a [Country].
 *
 * [code] is the value sent to the backend in the `state` field of the profile update
 * request (ISO2 code for US states, province code for Canada); [name] is the
 * user-facing display label.
 */
@JsonClass(generateAdapter = true)
data class State(
    @Json(name = "code") val code: String,
    @Json(name = "name") val name: String
)
