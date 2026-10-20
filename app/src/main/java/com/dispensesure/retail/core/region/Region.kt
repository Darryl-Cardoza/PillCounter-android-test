package com.dispensesure.retail.core.region

import com.dispensesure.retail.core.utils.common.DateFormats

/** The logged-in user's country. Every US/Canada difference reads a property from here. */
enum class Region(
    /** An 8-digit plain barcode is a DIN, looked up as scanned; other plain codes still pad to GTIN-14. */
    val eightDigitCodesAreDins: Boolean,
    /** Digit counts a plain (non-GS1) product barcode can have. */
    val plainProductCodeLengths: IntRange,
    /** HL7 orders may carry an 8-digit DIN where the HL7 library only accepts an NDC. */
    val hl7OrdersAcceptDin: Boolean,
    /** Pharmacies sort stock into buckets (Normal, 340B…); Canada has none. */
    val showsBucket: Boolean,
    /** Numeric date on screens and photos, e.g. an expiry. */
    val numericDatePattern: String,
    /** Numeric date with time on screens and photos. */
    val numericDateTimePattern: String,
) {
    US(
        eightDigitCodesAreDins = false,
        plainProductCodeLengths = 12..14,
        hl7OrdersAcceptDin = false,
        showsBucket = true,
        numericDatePattern = DateFormats.MM_DD_YY,
        numericDateTimePattern = DateFormats.MM_DD_YY_HH_MM_A,
    ),
    CA(
        eightDigitCodesAreDins = true,
        plainProductCodeLengths = 8..14,
        hl7OrdersAcceptDin = true,
        showsBucket = false,
        numericDatePattern = DateFormats.YYYY_MM_DD,
        numericDateTimePattern = DateFormats.YYYY_MM_DD_HH_MM_A,
    );

    companion object {
        /** Null for anything other than US / CA. */
        fun fromCountryCode(countryCode: String?): Region? {
            val normalizedCountryCode = countryCode?.trim()?.uppercase() ?: return null
            return entries.firstOrNull { region -> region.name == normalizedCountryCode }
        }
    }
}
