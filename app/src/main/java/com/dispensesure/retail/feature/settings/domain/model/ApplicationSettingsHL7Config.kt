package com.dispensesure.retail.feature.settings.domain.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * HL7 configuration returned by the settings API.
 *
 * The two host names are the mDNS service types this device advertises on and discovers
 * against. Nullable so a response that omits one degrades to the Hl7ServiceConfig default
 * instead of failing deserialization of the whole settings payload.
 */
@JsonClass(generateAdapter = true)
data class ApplicationSettingsHL7Config(
    @Json(name = "pms_host_name") val pmsHostName: String? = null,
    @Json(name = "pillcounter_host_name") val pillCounterHostName: String? = null,
    @Json(name = "barcode_format") val barcodeFormat: String,
)
