package com.rite.pillcounting.feature.settings.domain.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * HL7 configuration returned by the settings API.
 *
 * pmsHostName and pillCounterHostName have been removed — they are static
 * mDNS protocol constants that do not vary per pharmacy and were unnecessarily
 * exposing internal network topology through the API response.
 * They are now hardcoded in Hl7ServiceConfig.
 */
@JsonClass(generateAdapter = true)
data class ApplicationSettingsHL7Config(
    @Json(name = "barcode_format") val barcodeFormat: String,
)
