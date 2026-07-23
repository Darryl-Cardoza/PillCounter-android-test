package com.rite.pillcounting.feature.profile.data

import com.rite.pillcounting.feature.profile.domain.model.State

/**
 * Mock source of selectable states/provinces for the profile screen's state dropdown,
 * scoped per country code.
 *
 * Static placeholder until a backend state list endpoint is available.
 */
object StateData {
    private val usStates: List<State> = listOf(
        State(code = "AL", name = "Alabama"),
        State(code = "AK", name = "Alaska"),
        State(code = "AZ", name = "Arizona"),
        State(code = "AR", name = "Arkansas"),
        State(code = "CA", name = "California"),
        State(code = "CO", name = "Colorado"),
        State(code = "CT", name = "Connecticut"),
        State(code = "DE", name = "Delaware"),
        State(code = "FL", name = "Florida"),
        State(code = "GA", name = "Georgia"),
        State(code = "HI", name = "Hawaii"),
        State(code = "ID", name = "Idaho"),
        State(code = "IL", name = "Illinois"),
        State(code = "IN", name = "Indiana"),
        State(code = "IA", name = "Iowa"),
        State(code = "KS", name = "Kansas"),
        State(code = "KY", name = "Kentucky"),
        State(code = "LA", name = "Louisiana"),
        State(code = "ME", name = "Maine"),
        State(code = "MD", name = "Maryland"),
        State(code = "MA", name = "Massachusetts"),
        State(code = "MI", name = "Michigan"),
        State(code = "MN", name = "Minnesota"),
        State(code = "MS", name = "Mississippi"),
        State(code = "MO", name = "Missouri"),
        State(code = "MT", name = "Montana"),
        State(code = "NE", name = "Nebraska"),
        State(code = "NV", name = "Nevada"),
        State(code = "NH", name = "New Hampshire"),
        State(code = "NJ", name = "New Jersey"),
        State(code = "NM", name = "New Mexico"),
        State(code = "NY", name = "New York"),
        State(code = "NC", name = "North Carolina"),
        State(code = "ND", name = "North Dakota"),
        State(code = "OH", name = "Ohio"),
        State(code = "OK", name = "Oklahoma"),
        State(code = "OR", name = "Oregon"),
        State(code = "PA", name = "Pennsylvania"),
        State(code = "RI", name = "Rhode Island"),
        State(code = "SC", name = "South Carolina"),
        State(code = "SD", name = "South Dakota"),
        State(code = "TN", name = "Tennessee"),
        State(code = "TX", name = "Texas"),
        State(code = "UT", name = "Utah"),
        State(code = "VT", name = "Vermont"),
        State(code = "VA", name = "Virginia"),
        State(code = "WA", name = "Washington"),
        State(code = "WV", name = "West Virginia"),
        State(code = "WI", name = "Wisconsin"),
        State(code = "WY", name = "Wyoming"),
        State(code = "PR", name = "Puerto Rico"),
        State(code = "AP", name = "Armed Forces Pacific"),
        State(code = "DC", name = "District of Columbia"),
        State(code = "AA", name = "United States Armed Forces"),
        State(code = "AE", name = "Armed Forces Africa, Canada, Europe, Middle East"),
        State(code = "VI", name = "Virgin Islands")
    )

    private val caProvinces: List<State> = listOf(
        State(code = "AB", name = "Alberta"),
        State(code = "BC", name = "British Columbia"),
        State(code = "MB", name = "Manitoba"),
        State(code = "NB", name = "New Brunswick"),
        State(code = "NL", name = "Newfoundland and Labrador"),
        State(code = "NT", name = "Northwest Territories"),
        State(code = "NS", name = "Nova Scotia"),
        State(code = "NU", name = "Nunavut"),
        State(code = "ON", name = "Ontario"),
        State(code = "PE", name = "Prince Edward Island"),
        State(code = "QC", name = "Quebec"),
        State(code = "SK", name = "Saskatchewan"),
        State(code = "YT", name = "Yukon")
    )

    private val statesByCountry: Map<String, List<State>> = mapOf(
        "US" to usStates,
        "CA" to caProvinces
    )

    fun statesFor(countryCode: String?): List<State> =
        statesByCountry[countryCode].orEmpty()
}
