package com.rite.pillcounting.feature.dashboard.data

import com.rite.pillcounting.core.auth.AuthEvent
import com.rite.pillcounting.core.auth.AuthEventBus
import com.rite.pillcounting.core.refreshToken.domain.model.RefreshTokenRequest
import com.rite.pillcounting.core.refreshToken.domain.model.RefreshTokenResponse
import com.rite.pillcounting.feature.settings.data.remote.IApplicationSettingInterface
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.dashboard.data.remote.ITerminalApi
import com.rite.pillcounting.feature.dashboard.domain.model.TerminalListResponse
import com.rite.pillcounting.feature.dashboard.domain.model.TerminalUpdateRequest
import com.rite.pillcounting.feature.dashboard.domain.model.TerminalUpdateResponse
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import javax.inject.Inject

/**
 * Repository responsible for managing terminal update operations.
 *
 * It automatically handles:
 * - Authorization headers via [PreferenceHelper].
 * - Token refresh when encountering HTTP 401 (Invalid or expired token).
 */
class TerminalRepository @Inject constructor(
    private val terminalApi: ITerminalApi,
    private val ioDispatcher: CoroutineDispatcher,
    private val preferenceHelper: PreferenceHelper,
    private val applicationSettingApi: IApplicationSettingInterface,
    private val authEventBus: AuthEventBus
) {

    private val logger = AppLogger.create<TerminalRepository>()

    /**
     * Update terminal settings on the remote server.
     *
     * @param terminalId The ID of the terminal to update.
     * @param request Terminal update request body.
     * @return [Result] containing [TerminalUpdateResponse] on success, or an exception on failure.
     */
    suspend fun updateTerminal(
        terminalId: String,
        request: TerminalUpdateRequest
    ): Result<TerminalUpdateResponse> = withContext(ioDispatcher) {
        try {
            logger.i("Updating terminal: $terminalId with name: ${request.terminalName}, active: ${request.isActive}")
            val token = preferenceHelper.getAccessToken().orEmpty()
            val response = terminalApi.updateTerminal("Bearer $token", terminalId, request)
            logger.i("Terminal update successful.")
            Result.success(response)

        } catch (e: HttpException) {
            if (e.code() == 401) {
                logger.w("Access token invalid or expired. Attempting refresh...")

                return@withContext handleTokenRefreshAndRetry {
                    val newToken = preferenceHelper.getAccessToken().orEmpty()
                    terminalApi.updateTerminal("Bearer $newToken", terminalId, request)
                }
            }
            logger.e("Terminal update failed with HttpException", e)
            Result.failure(e)
        } catch (e: Exception) {
            logger.e("Terminal update failed", e)
            Result.failure(e)
        }
    }

    /**
     * Fetches the list of terminals for the pharmacy.
     *
     * @param availableOnly When true, restricts to free terminals plus the one [deviceKey] already holds.
     * @param deviceKey Stable per-device identifier (SSAID / Settings.Secure.ANDROID_ID). Survives reinstall with the same signing key; reset by factory reset.
     * @return [Result] containing [TerminalListResponse] on success, or an exception on failure.
     */
    suspend fun getTerminals(
        availableOnly: Boolean,
        deviceKey: String
    ): Result<TerminalListResponse> = withContext(ioDispatcher) {
        try {
            logger.i("Fetching terminals (availableOnly=$availableOnly)")
            val token = preferenceHelper.getAccessToken().orEmpty()
            val response = terminalApi.getTerminals("Bearer $token", availableOnly, deviceKey)
            Result.success(response)
        } catch (e: HttpException) {
            if (e.code() == 401) {
                logger.w("Access token invalid or expired. Attempting refresh...")

                return@withContext handleTokenRefreshAndRetry {
                    val newToken = preferenceHelper.getAccessToken().orEmpty()
                    terminalApi.getTerminals("Bearer $newToken", availableOnly, deviceKey)
                }
            }
            logger.e("Fetching terminals failed with HttpException", e)
            Result.failure(e)
        } catch (e: Exception) {
            logger.e("Fetching terminals failed", e)
            Result.failure(e)
        }
    }

    // ─────────────────────────── Token Refresh Handler ───────────────────────────
    /**
     * Handles access token refresh logic and retries the failed API call.
     */
    private suspend fun <T> handleTokenRefreshAndRetry(apiCall: suspend () -> T): Result<T> {
        return try {
            val refreshToken = preferenceHelper.getRefreshToken()
                ?: return Result.failure(Exception("No refresh token available"))

            val refreshResponse = applicationSettingApi.refreshToken(RefreshTokenRequest(refreshToken))
            if (refreshResponse.code() == 401) {
                // Refresh itself was rejected — session is truly expired. Broadcast so
                // MainActivity performs the standard logout teardown + Login nav.
                logger.e("Refresh token rejected (401) — publishing SessionExpired")
                authEventBus.tryPublish(AuthEvent.SessionExpired)
                return Result.failure(Exception("Refresh returned 401"))
            }
            val refreshResponseBody: RefreshTokenResponse? = refreshResponse.body()
            if (!refreshResponseBody?.accessToken.isNullOrBlank()) {
                logger.i("Token refreshed successfully.")
                preferenceHelper.saveTokens(
                    accessToken = refreshResponseBody?.accessToken!!,
                    refreshToken = refreshResponseBody.refreshToken ?: refreshToken
                )

                // Retry API with new token
                val retryResponse = apiCall()
                logger.i("API retried successfully after token refresh.")
                Result.success(retryResponse)
            } else {
                logger.e("Token refresh failed: ${refreshResponseBody?.message}")
                Result.failure(Exception("Failed to refresh token: ${refreshResponseBody?.message}"))
            }
        } catch (ex: Exception) {
            logger.e("Token refresh or retry failed", ex)
            Result.failure(ex)
        }
    }
}

