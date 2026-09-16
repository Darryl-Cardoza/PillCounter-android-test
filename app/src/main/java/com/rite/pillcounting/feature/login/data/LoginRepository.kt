package com.rite.pillcounting.feature.login.data

import com.rite.pillcounting.core.room.dao.UserDao
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.feature.login.data.remote.ILoginApi
import com.rite.pillcounting.feature.login.domain.data.ILoginRepository
import com.rite.pillcounting.feature.login.domain.model.LoginRequest
import com.rite.pillcounting.feature.login.domain.model.LoginResponse
import com.rite.pillcounting.feature.login.domain.model.LogoutRequest
import com.rite.pillcounting.feature.login.domain.model.LogoutResponse
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Default implementation of [ILoginRepository] that interacts with both
 * a remote API and a local Room database.
 *
 * @property userDao DAO for user-related local persistence.
 * @property loginApi Retrofit service for network authentication.
 * @property ioDispatcher Coroutine dispatcher for offloading I/O operations.
 */
class LoginRepository @Inject constructor(
    private val userDao: UserDao,
    private val loginApi: ILoginApi,
    private val ioDispatcher: CoroutineDispatcher
) : ILoginRepository {

    private val logger = AppLogger.create<LoginRepository>()

    /**
     * Authenticates the user against the remote API.
     *
     * @param username The email or username used for login.
     * @return A [Result] wrapping either a [LoginResponse] on success
     * or an exception on failure.
     */
    override suspend fun login(username: String): Result<LoginResponse> =
        withContext(ioDispatcher) {
            try {
                logger.i("Starting login for user: $username")
                val request = LoginRequest(email = username)
                val response = loginApi.login(request)
                logger.i("Login successful for user: $username")
                Result.success(response)
            } catch (e: Exception) {
                logger.e("Login failed for user: $username", e)
                Result.failure(e)
            }
        }

    /**
     * Logs out the user by invalidating the refresh token on the server.
     *
     * @param refreshToken The refresh token to invalidate.
     * @return A [Result] wrapping either a [LogoutResponse] on success
     * or an exception on failure.
     */
    override suspend fun logout(refreshToken: String): Result<LogoutResponse> =
        withContext(ioDispatcher) {
            try {
                logger.i("Logging out (refresh token present: ${refreshToken.isNotBlank()})")
                val request = LogoutRequest(refreshToken = refreshToken)
                val response = loginApi.logout(request)
                logger.i("Logout successful.")
                Result.success(response)
            } catch (e: Exception) {
                logger.e("Logout failed.", e)
                Result.failure(e)
            }
        }
}
