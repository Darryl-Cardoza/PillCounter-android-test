package com.rite.pillcounting.feature.verifyPin.domain.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Represents the data structure for a verify OTP API request.
 *
 * @property email The user's email address to associate the OTP with.
 * @property otp The one-time password entered by the user.
 * @property fcmToken Firebase push token for this install.
 * @property deviceKey Stable per-device identifier (SSAID / Settings.Secure.ANDROID_ID). Survives reinstall with the same signing key; reset by factory reset.
 * @property platform Client platform, always "android" for this app.
 * @property appVersion The app's version name.
 */
@JsonClass(generateAdapter = true)
data class VerifyPinRequest(
    @Json(name = "email")
    val email: String,

    @Json(name = "otp")
    val otp: String,

    @Json(name = "fcm_token")
    val fcmToken: String,

    @Json(name = "device_key")
    val deviceKey: String,

    @Json(name = "platform")
    val platform: String,

    @Json(name = "app_version")
    val appVersion: String
)
