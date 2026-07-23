package com.rite.pillcounting.feature.profile.domain.model

/**
 * Represents a country selectable in the profile screen.
 *
 * [code] is the ISO 3166-1 alpha-2 value sent to the backend in the `country`
 * field of the profile update request; [name] is the user-facing display label.
 */
data class Country(
    val code: String,
    val name: String
)
