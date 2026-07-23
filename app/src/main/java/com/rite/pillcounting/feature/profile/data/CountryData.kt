package com.rite.pillcounting.feature.profile.data

import com.rite.pillcounting.feature.profile.domain.model.Country

/**
 * Mock source of selectable countries for the profile screen's country dropdown.
 *
 * Static placeholder until a backend country list endpoint is available.
 */
object CountryData {
    val countries: List<Country> = listOf(
        Country(code = "US", name = "United States"),
        Country(code = "CA", name = "Canada")
    )

    const val DEFAULT_COUNTRY_CODE = "US"
}
