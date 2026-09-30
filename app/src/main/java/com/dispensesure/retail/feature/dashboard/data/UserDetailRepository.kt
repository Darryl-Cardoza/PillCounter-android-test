package com.dispensesure.retail.feature.dashboard.data

import com.dispensesure.retail.BuildConfig
import com.dispensesure.retail.core.auth.AuthEvent
import com.dispensesure.retail.core.auth.AuthEventBus
import com.dispensesure.retail.core.models.ApiResponse
import com.dispensesure.retail.core.refreshToken.domain.model.RefreshTokenRequest
import com.dispensesure.retail.core.refreshToken.domain.model.UserDetailRequest
import com.dispensesure.retail.feature.settings.data.remote.IApplicationSettingInterface
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.logger.LogEvent
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import com.dispensesure.retail.feature.dashboard.data.remote.IUserDetailAPI
import com.dispensesure.retail.feature.dashboard.domain.data.IUserDetailRepository
import com.dispensesure.retail.feature.dashboard.domain.model.UserDetail
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import javax.inject.Inject

/**
 * Repository for fetching user details from the backend.
 *
 * - Automatically adds bearer token from [PreferenceHelper].
 * - Refreshes tokens if access token is invalid or expired (HTTP 401).
 * - Retries the API call seamlessly after refreshing the token.
 */
class UserDetailRepository @Inject constructor(
    private val api: IUserDetailAPI,
    private val applicationSettingApi: IApplicationSettingInterface,
    private val preferenceHelper: PreferenceHelper,
    private val ioDispatcher: CoroutineDispatcher,
    private val authEventBus: AuthEventBus
) : IUserDetailRepository {

    private val logger = AppLogger.create<UserDetailRepository>()

    /**
     * Fetches user details using the stored access token.
     * If the access token is invalid, it attempts a token refresh and retries the request.
     */
    override suspend fun getUserDetail(token: String): Result<ApiResponse<UserDetail>> =
        withContext(ioDispatcher) {
            try {
                logger.i("Fetching user detail with token: ${token.take(10)}...")
                // Fetch the latest FCM token asynchronously
//                val fcmToken =
//                    com.google.firebase.messaging.FirebaseMessaging.getInstance().token.await()
                val request = UserDetailRequest(
//                    fcmToken = fcmToken,
                    platform = "android",
                    appVersion = BuildConfig.VERSION_NAME
                )

                val response = api.getUserDetail("Bearer $token", request)


                val result: Result<ApiResponse<UserDetail>> = when {
                    response.isSuccessful -> {
                        response.body()?.let {
                            logger.i("User detail fetched successfully.")
                            Result.success(it)
                        } ?: run {
                            logger.e("Empty response body while fetching user detail.", event = LogEvent.USER_FETCH_FAILED)
                            Result.failure(Exception("Empty response body"))
                        }
                    }

                    response.code() == 401 -> {
                        logger.w("Access token invalid or expired. Attempting refresh...")
                        handleTokenRefreshAndRetry(
                            apiCall = {
                                val newToken = preferenceHelper.getAccessToken().orEmpty()
                                val retryResponse = api.getUserDetail("Bearer $newToken", request)
                                if (retryResponse.isSuccessful) {
                                    retryResponse.body()?.let {
                                        logger.i("User detail fetched successfully after token refresh.")
                                        Result.success(it)
                                    }
                                        ?: Result.failure(Exception("Empty response body after retry"))
                                } else {
                                    val failure = Exception("Failed after token refresh: HTTP ${retryResponse.code()}")
                                    logger.e("User detail retry after token refresh failed", failure, event = LogEvent.USER_FETCH_FAILED)
                                    Result.failure(failure)
                                }
                            },
                            onLogout = {
                                preferenceHelper.clearTokens()
                                preferenceHelper.setUserLoggedIn(false)
                                // A refresh call that itself returned 401 is the "session
                                // really expired" signal — broadcast so MainActivity nav to Login.
                                authEventBus.tryPublish(AuthEvent.SessionExpired)
                            }
                        )
                    }

                    else -> {
                        logger.e("Error fetching user detail. HTTP code: ${response.code()}", event = LogEvent.USER_FETCH_FAILED)
                        Result.failure(Exception("Server returned ${response.code()}"))
                    }
                }

                result //
            } catch (e: HttpException) {
                logger.e("HttpException during getUserDetail()", e, event = LogEvent.USER_FETCH_FAILED)
                Result.failure(e)
            } catch (e: Exception) {
                logger.e("Unexpected error fetching user detail", e, event = LogEvent.USER_FETCH_FAILED)
                Result.failure(e)
            }
        }


    // ─────────────────────────── Token Refresh & Retry Handler ───────────────────────────
    /**
     * Handles access token refresh and retries the failed API request.
     *
     * @param apiCall A suspend function representing the API to retry after refresh.
     * @return [Result] wrapping success or failure.
     */
    private suspend fun <T> handleTokenRefreshAndRetry(
        apiCall: suspend () -> Result<T>,
        onLogout: () -> Unit
    ): Result<T> {
        return try {
            val refreshToken = preferenceHelper.getRefreshToken()
                ?: return Result.failure<T>(Exception("No refresh token available")).also {
                    logger.e("Token refresh skipped: no refresh token available", it.exceptionOrNull(), event = LogEvent.TOKEN_REFRESH_FAILED)
                }

            val refreshResponse = applicationSettingApi.refreshToken(RefreshTokenRequest(refreshToken))


            if (refreshResponse.code() == 401) {
                logger.e("Refresh token expired or invalid — logging out user.", event = LogEvent.TOKEN_REFRESH_FAILED)
                onLogout()
                return Result.failure(Exception("LOGOUT"))
            }
            val refreshResponseBody = refreshResponse.body()
            if (!refreshResponseBody?.accessToken.isNullOrBlank()) {
                logger.i("Token refreshed successfully. Saving new tokens.")
                preferenceHelper.saveTokens(
                    accessToken = refreshResponseBody?.accessToken!!,
                    refreshToken = refreshResponseBody.refreshToken ?: refreshToken
                )

                // Retry API call with new access token
                apiCall()
            } else {
                logger.e("Token refresh failed: ${refreshResponseBody?.message}", event = LogEvent.TOKEN_REFRESH_FAILED)
                Result.failure(Exception("Failed to refresh token: ${refreshResponseBody?.message}"))
            }
        } catch (ex: Exception) {
            logger.e("Token refresh or retry failed", ex, event = LogEvent.TOKEN_REFRESH_FAILED)
            Result.failure(ex)
        }
    }
}
