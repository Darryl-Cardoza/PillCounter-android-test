package com.rite.pillcounting.feature.profile.data

import com.rite.pillcounting.core.refreshToken.domain.model.RefreshTokenRequest
import com.rite.pillcounting.core.refreshToken.domain.model.RefreshTokenResponse
import com.rite.pillcounting.feature.settings.data.remote.IApplicationSettingInterface
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.profile.data.remote.IProfileApi
import com.rite.pillcounting.feature.profile.domain.data.IProfileRepository
import com.rite.pillcounting.feature.profile.domain.model.Country
import com.rite.pillcounting.feature.profile.domain.model.PharmacyTypeResponse
import com.rite.pillcounting.feature.profile.domain.model.ProfileDeleteResponse
import com.rite.pillcounting.feature.profile.domain.model.ProfileUpdateRequest
import com.rite.pillcounting.feature.profile.domain.model.ProfileUpdateResponse
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import javax.inject.Inject

/**
 * Repository responsible for managing user profile update and delete operations.
 *
 * It automatically handles:
 * - Authorization headers via [PreferenceHelper].
 * - Token refresh when encountering HTTP 401 (Invalid or expired token).
 */
class ProfileRepository @Inject constructor(
    private val profileApi: IProfileApi,
    private val ioDispatcher: CoroutineDispatcher,
    private val preferenceHelper: PreferenceHelper,
    private val applicationSettingApi: IApplicationSettingInterface
) : IProfileRepository {

    private val logger = AppLogger.create<ProfileRepository>()

    /**
     * Update the user profile on the remote server.
     *
     * @param request Profile update request body.
     * @return [Result] containing [ProfileUpdateResponse] on success, or an exception on failure.
     */
    override suspend fun updateProfile(
        request: ProfileUpdateRequest
    ): Result<ProfileUpdateResponse> = withContext(ioDispatcher) {
        try {
            logger.i("Updating profile for user: ${request.fName} ${request.lName}")
            val token = preferenceHelper.getAccessToken().orEmpty()
            val response = profileApi.updateProfile("Bearer $token", request)
            logger.i("Profile update successful.")
            Result.success(response)

        } catch (e: HttpException) {
            if (e.code() == 401) {
                logger.w("Access token invalid or expired. Attempting refresh...")

                return@withContext handleTokenRefreshAndRetry {
                    val newToken = preferenceHelper.getAccessToken().orEmpty()
                    profileApi.updateProfile("Bearer $newToken", request)
                }
            }
            logger.e("Profile update failed with HttpException", e)
            Result.failure(e)
        } catch (e: Exception) {
            logger.e("Profile update failed", e)
            Result.failure(e)
        }
    }

    /**
     * Delete the user profile permanently from the remote server.
     *
     * @return [Result] containing [ProfileDeleteResponse] on success, or an exception on failure.
     */
    override suspend fun deleteProfile(): Result<ProfileDeleteResponse> = withContext(ioDispatcher) {
        try {
            logger.i("Deleting user profile.")
            val token = preferenceHelper.getAccessToken().orEmpty()
            val response = profileApi.deleteProfile("Bearer $token")
            logger.i("Profile deletion successful.")
            Result.success(response)

        } catch (e: HttpException) {
            if (e.code() == 401) {
                logger.w("Access token invalid or expired. Attempting refresh...")

                return@withContext handleTokenRefreshAndRetry {
                    val newToken = preferenceHelper.getAccessToken().orEmpty()
                    profileApi.deleteProfile("Bearer $newToken")
                }
            }
            logger.e("Profile delete failed with HttpException", e)
            Result.failure(e)
        } catch (e: Exception) {
            logger.e("Profile deletion failed", e)
            Result.failure(e)
        }
    }

    /**
     * Fetches the reference list of countries and their states/provinces.
     *
     * @return [Result] containing the list of [Country], or an exception on failure.
     */
    override suspend fun getCountries(): Result<List<Country>> = withContext(ioDispatcher) {
        try {
            logger.i("Fetching countries reference list.")
            val response = profileApi.getCountries()
            Result.success(response.data?.countries.orEmpty())
        } catch (e: Exception) {
            logger.e("Failed to fetch countries reference list", e)
            Result.failure(e)
        }
    }

    /**
     * Fetch the list of selectable pharmacy types from the remote server.
     *
     * @return [Result] containing [PharmacyTypeResponse] on success, or an exception on failure.
     */
    override suspend fun getPharmacyTypes(): Result<PharmacyTypeResponse> = withContext(ioDispatcher) {
        try {
            logger.i("Fetching pharmacy types.")
            val token = preferenceHelper.getAccessToken().orEmpty()
            val response = profileApi.getPharmacyTypes("Bearer $token")
            logger.i("Pharmacy types fetched successfully.")
            Result.success(response)

        } catch (e: HttpException) {
            if (e.code() == 401) {
                logger.w("Access token invalid or expired. Attempting refresh...")

                return@withContext handleTokenRefreshAndRetry {
                    val newToken = preferenceHelper.getAccessToken().orEmpty()
                    profileApi.getPharmacyTypes("Bearer $newToken")
                }
            }
            logger.e("Fetching pharmacy types failed with HttpException", e)
            Result.failure(e)
        } catch (e: Exception) {
            logger.e("Fetching pharmacy types failed", e)
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
