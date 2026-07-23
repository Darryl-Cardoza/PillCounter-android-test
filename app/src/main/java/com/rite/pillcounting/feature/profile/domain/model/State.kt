package com.rite.pillcounting.feature.profile.domain.model

/**
 * Represents a state/province selectable in the profile screen, scoped to a [Country].
 *
 * [code] is the value sent to the backend in the `state` field of the profile update
 * request (ISO2 code for US states, province code for Canada); [name] is the
 * user-facing display label.
 */
data class State(
    val code: String,
    val name: String
)
