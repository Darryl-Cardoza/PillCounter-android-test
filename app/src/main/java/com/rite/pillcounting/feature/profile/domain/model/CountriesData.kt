package com.rite.pillcounting.feature.profile.domain.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Payload of the `/reference/countries` response, wrapped by [com.rite.pillcounting.core.models.ApiResponse].
 */
@JsonClass(generateAdapter = true)
data class CountriesData(
    @Json(name = "countries") val countries: List<Country>? = null
)
