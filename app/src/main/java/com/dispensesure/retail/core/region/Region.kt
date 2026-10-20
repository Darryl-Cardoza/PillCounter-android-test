package com.dispensesure.retail.core.region

/** The logged-in user's country. Every US/Canada difference reads a property from here. */
enum class Region(
    /** Plain barcodes shorter than 14 digits are padded to a GTIN-14 before lookup. */
    val padsShortBarcodes: Boolean,
    /** Digit counts a plain (non-GS1) product barcode can have. */
    val plainProductCodeLengths: IntRange,
) {
    US(padsShortBarcodes = true, plainProductCodeLengths = 12..14),
    CA(padsShortBarcodes = false, plainProductCodeLengths = 8..14);

    companion object {
        /** Null for anything other than US / CA. */
        fun fromCountryCode(countryCode: String?): Region? {
            val normalizedCountryCode = countryCode?.trim()?.uppercase() ?: return null
            return entries.firstOrNull { region -> region.name == normalizedCountryCode }
        }
    }
}
