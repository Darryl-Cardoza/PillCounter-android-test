package com.rite.pillcounting.core.refreshToken.data

import com.rite.pillcounting.core.refreshToken.data.remote.IRefreshTokenAPI
import com.rite.pillcounting.core.refreshToken.domain.model.RefreshTokenRequest
import com.rite.pillcounting.core.refreshToken.domain.model.RefreshTokenResponse
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [TokenAuthenticator].
 *
 * Covers: successful refresh + retry, missing refresh token, refresh API exception,
 * null response, null access token, refresh-token-omitted fallback, and the
 * responseCount loop guard.
 */
class TokenAuthenticatorTest {

    private lateinit var prefs: PreferenceHelper
    private lateinit var refreshApi: IRefreshTokenAPI
    private lateinit var authenticator: TokenAuthenticator

    private val url = "https://example.com/api/resource"

    @Before
    fun setup() {
        prefs = mockk(relaxed = true)
        refreshApi = mockk()
        authenticator = TokenAuthenticator(prefs, refreshApi)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    /** Builds a real 401 Response, optionally chaining [priorCount] prior responses. */
    private fun buildResponse(priorCount: Int = 0): Response {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer oldToken")
            .build()

        var builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")

        if (priorCount > 0) {
            var prior: Response? = null
            repeat(priorCount) {
                prior = Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(401)
                    .message("Unauthorized")
                    .apply { if (prior != null) priorResponse(prior!!) }
                    .build()
            }
            builder = builder.priorResponse(prior!!)
        }

        return builder.build()
    }

    @Test
    fun `returns request with new authorization header on successful refresh`() {
        every { prefs.getRefreshToken() } returns "refresh"
        coEvery { refreshApi.refreshToken(RefreshTokenRequest("refresh")) } returns
            RefreshTokenResponse(accessToken = "newAccess", refreshToken = "newRefresh")

        val result = authenticator.authenticate(null, buildResponse())

        assertEquals("Bearer newAccess", result?.header("Authorization"))
        assertEquals(url, result?.url.toString())
        verify(exactly = 1) { prefs.saveTokens("newAccess", "newRefresh") }
    }

    @Test
    fun `keeps current refresh token when refresh response omits one`() {
        every { prefs.getRefreshToken() } returns "refresh"
        coEvery { refreshApi.refreshToken(any()) } returns
            RefreshTokenResponse(accessToken = "newAccess", refreshToken = null)

        val result = authenticator.authenticate(null, buildResponse())

        assertEquals("Bearer newAccess", result?.header("Authorization"))
        verify(exactly = 1) { prefs.saveTokens("newAccess", "refresh") }
    }

    @Test
    fun `returns null when there is no refresh token`() {
        every { prefs.getRefreshToken() } returns null

        val result = authenticator.authenticate(null, buildResponse())

        assertNull(result)
        verify(exactly = 0) { prefs.saveTokens(any(), any()) }
    }

    @Test
    fun `returns null when refresh api throws`() {
        every { prefs.getRefreshToken() } returns "refresh"
        coEvery { refreshApi.refreshToken(any()) } throws RuntimeException("network down")

        val result = authenticator.authenticate(null, buildResponse())

        assertNull(result)
        verify(exactly = 0) { prefs.saveTokens(any(), any()) }
    }

    @Test
    fun `returns null when refresh response has null access token`() {
        every { prefs.getRefreshToken() } returns "refresh"
        coEvery { refreshApi.refreshToken(any()) } returns
            RefreshTokenResponse(accessToken = null, refreshToken = "newRefresh")

        val result = authenticator.authenticate(null, buildResponse())

        assertNull(result)
        verify(exactly = 0) { prefs.saveTokens(any(), any()) }
    }

    @Test
    fun `returns null when response count reaches the retry limit`() {
        // One prior response => responseCount == 2 => loop guard triggers.
        val result = authenticator.authenticate(null, buildResponse(priorCount = 1))

        assertNull(result)
        verify(exactly = 0) { prefs.getRefreshToken() }
        verify(exactly = 0) { prefs.saveTokens(any(), any()) }
    }
}
