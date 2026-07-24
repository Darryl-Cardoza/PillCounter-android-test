package com.rite.pillcounting.feature.profile.domain.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Represents a country selectable in the profile screen.
 *
 * [code] is the ISO 3166-1 alpha-2 value sent to the backend in the `country`
 * field of the profile update request; [name] is the user-facing display label;
 * [states] is the country's selectable states/provinces, as returned by the
 * `/reference/countries` endpoint.
 */
@JsonClass(generateAdapter = true)
data class Country(
    @Json(name = "code") val code: String,
    @Json(name = "name") val name: String,
    @Json(name = "states") val states: List<State> = emptyList()
)
