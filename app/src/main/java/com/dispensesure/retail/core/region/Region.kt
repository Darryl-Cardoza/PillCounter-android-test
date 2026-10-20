package com.dispensesure.retail.core.region

/** The logged-in user's country. Every US/Canada difference reads a property from here. */
enum class Region {
    US,
    CA;

    companion object {
        /** Null for anything other than US / CA. */
        fun fromCountryCode(countryCode: String?): Region? {
            val normalizedCountryCode = countryCode?.trim()?.uppercase() ?: return null
            return entries.firstOrNull { region -> region.name == normalizedCountryCode }
        }
    }
}
