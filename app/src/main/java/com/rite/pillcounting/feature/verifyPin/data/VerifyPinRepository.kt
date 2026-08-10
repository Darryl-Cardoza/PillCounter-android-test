package com.rite.pillcounting.feature.otp.data

import com.rite.pillcounting.core.utils.constants.AppConstants
import com.rite.pillcounting.core.utils.notification.FCMService
import com.rite.pillcounting.feature.verifyPin.data.remote.IVerifyPinAPI
import com.rite.pillcounting.feature.verifyPin.domain.data.IVerifyPinRepository
import com.rite.pillcounting.feature.verifyPin.domain.model.VerifyPinRequest
import com.rite.pillcounting.feature.verifyPin.domain.model.VerifyPinResponse
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The default implementation of [IVerifyPinRepository] that interacts with a remote API.
 *
 * This repository is responsible for handling all data operations related to OTP (One-Time Password)
 * verification by communicating with the remote server.
 *
 * @property verifyPinApi The Retrofit service for making network authentication requests, which includes the verify endpoint.
 * @property fcmService Supplies this install's current FCM registration token.
 * @property ioDispatcher The coroutine dispatcher for running all network operations on a background thread.
 */
class VerifyPinRepository @Inject constructor(
    private val verifyPinApi: IVerifyPinAPI,
    private val fcmService: FCMService,
    private val ioDispatcher: CoroutineDispatcher
) : IVerifyPinRepository {

    /**
     * Executes the OTP verification request against the remote API on an I/O-optimized thread.
     *
     * @param email The user's email address to associate with the OTP.
     * @param otp The one-time password entered by the user.
     * @param deviceKey Stable per-install device identifier (Firebase Installations ID).
     * @param appVersion The app's version name.
     * @return A [Result] wrapper containing the [VerifyPinResponse] on success or an exception on failure.
     */
    override suspend fun verifyPin(
        email: String,
        otp: String,
        deviceKey: String,
        appVersion: String
    ): Result<VerifyPinResponse> =
        withContext(ioDispatcher) {
            try {
                // Build the request payload
                val request = VerifyPinRequest(
                    email = email,
                    otp = otp,
                    fcmToken = fcmService.getToken().orEmpty(),
                    deviceKey = deviceKey,
                    platform = AppConstants.PLATFORM_ANDROID,
                    appVersion = appVersion
                )

                // Make API call
                val response = verifyPinApi.verifyPin(request)

                Result.success(response)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}

