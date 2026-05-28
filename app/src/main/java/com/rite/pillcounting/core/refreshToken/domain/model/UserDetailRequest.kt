package com.rite.pillcounting.core.refreshToken.domain.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class UserDetailRequest(
//    @Json(name = "fcm_token")
//    val fcmToken: String,
    @Json(name = "platform")
    val platform: String,
    @Json(name = "app_version")
    val appVersion: String
)
