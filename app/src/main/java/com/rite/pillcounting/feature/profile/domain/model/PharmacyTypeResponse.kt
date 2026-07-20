package com.rite.pillcounting.feature.profile.domain.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PharmacyTypeResponse(
    @Json(name = "status") val status: Int? = null,
    @Json(name = "is_success") val isSuccess: Boolean? = null,
    @Json(name = "message") val message: String? = null,
    @Json(name = "token") val token: String? = null,
    @Json(name = "data") val data: PharmacyTypeData? = null,
)

@JsonClass(generateAdapter = true)
data class PharmacyTypeData(
    @Json(name = "pharmacy_types") val pharmacyTypes: List<PharmacyTypeOption>? = null,
)

@JsonClass(generateAdapter = true)
data class PharmacyTypeOption(
    @Json(name = "code") val code: String,
    @Json(name = "label") val label: String,
) {
    val id: String
        get() = code
}
