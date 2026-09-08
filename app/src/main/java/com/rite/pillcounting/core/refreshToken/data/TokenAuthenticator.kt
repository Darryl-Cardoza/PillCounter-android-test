package com.rite.pillcounting.core.refreshToken.data

import com.rite.pillcounting.core.auth.AuthEvent
import com.rite.pillcounting.core.auth.AuthEventBus
import com.rite.pillcounting.core.refreshToken.data.remote.IRefreshTokenAPI
import com.rite.pillcounting.core.refreshToken.domain.model.RefreshTokenRequest
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import retrofit2.HttpException
import javax.inject.Inject

/**
 *
 * A custom [Authenticator] implementation that automatically refreshes expired access tokens
 * when the backend responds with a `401 Unauthorized` error.
 * It securely calls the refresh-token API, updates stored tokens, and retries the failed request.
 *
 */
class TokenAuthenticator @Inject constructor(
    private val prefs: PreferenceHelper,
    private val refreshApi: IRefreshTokenAPI,
    private val authEventBus: AuthEventBus
) : Authenticator {

    /**
     * Called automatically by OkHttp when a request receives a `401 Unauthorized` response.
     *
     * Performs a token refresh operation and retries the request with updated credentials.
     *
     * @param route The route to the target server (unused here).
     * @param response The failed [Response] triggering re-authentication.
     * @return A new [Request] with a refreshed `Authorization` header,
     *         or `null` if the refresh process fails (causing OkHttp to stop retrying).
     */
    override fun authenticate(route: Route?, response: Response): Request? {
        // Avoid infinite retry loops — only retry once per failed chain.
        if (responseCount(response) >= 2) return null

        val currentRefreshToken = prefs.getRefreshToken() ?: return null

        // Perform token refresh synchronously (blocking call). A refresh call that itself
        // returns HTTP 401 is the ONE condition that declares a session truly expired —
        // publish it to AuthEventBus so MainActivity can nav to Login and toast. Other
        // failure modes (network exception, empty body, non-401 error) are offline / bad
        // luck and stay quiet — the session may still be valid once the network recovers.
        val refreshResponse = runBlocking {
            try {
                refreshApi.refreshToken(RefreshTokenRequest(currentRefreshToken))
            } catch (e: HttpException) {
                if (e.code() == 401) {
                    authEventBus.tryPublish(AuthEvent.SessionExpired)
                }
                null
            } catch (e: Exception) {
                null
            }
        } ?: return null

        // Validate new tokens before proceeding
        val newAccessToken = refreshResponse.accessToken ?: return null
        val newRefreshToken = refreshResponse.refreshToken ?: currentRefreshToken

        // Save both tokens atomically
        prefs.saveTokens(newAccessToken, newRefreshToken)

        // Retry the failed request with the new Authorization header
        return response.request.newBuilder()
            .header("Authorization", "Bearer $newAccessToken")
            .build()
    }

    /**
     * Counts the number of prior responses for this request to detect
     * recursive retry attempts. Prevents infinite authentication loops.
     *
     * @param response The current [Response] object.
     * @return The number of nested prior responses.
     */
    private fun responseCount(response: Response): Int {
        var count = 1
        var priorResponse = response.priorResponse
        while (priorResponse != null) {
            count++
            priorResponse = priorResponse.priorResponse
        }
        return count
    }
}
