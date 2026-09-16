package com.rite.pillcounting.feature.verifyPin.domain.data

import com.rite.pillcounting.feature.verifyPin.domain.model.VerifyPinResponse

/**
 * Defines the contract for OTP verification data operations.
 */
interface IVerifyPinRepository {
    /**
     * Attempts to verify the user's OTP via the remote API.
     *
     * @param email The user's email address.
     * @param otp The one-time password.
     * @param deviceKey Stable per-device identifier (SSAID / Settings.Secure.ANDROID_ID).
     * Survives reinstall with the same signing key; reset by factory reset.
     * @param appVersion The app's version name.
     * @return A [Result] wrapper containing the [VerifyPinResponse] on success or an exception on failure.
     */
    suspend fun verifyPin(
        email: String,
        otp: String,
        deviceKey: String,
        appVersion: String
    ): Result<VerifyPinResponse>
}
