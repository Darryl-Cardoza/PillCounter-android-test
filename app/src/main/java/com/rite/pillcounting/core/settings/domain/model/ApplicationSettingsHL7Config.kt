package com.rite.pillcounting.core.settings.domain.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Represents the "settings" object, containing colors and logos.
 */
@JsonClass(generateAdapter = true)
data class ApplicationSettingsHL7Config(
    @Json(name = "pms_host_name") val pmsHostName: String,
    @Json(name = "pillcounter_host_name") val pillCounterHostName: String,
    @Json(name = "barcode_format") val barcodeFormat: String,
)
