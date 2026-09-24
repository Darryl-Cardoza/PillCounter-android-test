package com.dispensesure.retail.feature.login.domain.data

import com.dispensesure.retail.feature.login.domain.model.LoginResponse
import com.dispensesure.retail.feature.login.domain.model.LogoutResponse

/**
 * Contract for authentication-related data operations.
 */
interface ILoginRepository {

    /**
     * Attempts to authenticate a user with the given credentials via the remote API.
     */
    suspend fun login(
        username: String,
    ): Result<LoginResponse>

    /**
     * Logs out the user by invalidating the refresh token on the server.
     *
     * @param refreshToken The refresh token to invalidate.
     * @return A [Result] wrapping either:
     *   - [LogoutResponse] on success, or
     *   - an exception on failure.
     */
    suspend fun logout(refreshToken: String): Result<LogoutResponse>
}
