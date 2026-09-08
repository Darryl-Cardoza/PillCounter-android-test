package com.rite.pillcounting.feature.dashboard.data

import com.rite.pillcounting.core.auth.AuthEventBus
import com.rite.pillcounting.core.models.ApiResponse
import com.rite.pillcounting.core.refreshToken.domain.model.RefreshTokenRequest
import com.rite.pillcounting.core.refreshToken.domain.model.RefreshTokenResponse
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.dashboard.data.remote.IUserDetailAPI
import com.rite.pillcounting.feature.dashboard.domain.model.UserDetail
import com.rite.pillcounting.feature.settings.data.remote.IApplicationSettingInterface
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/**
 * Unit tests for [UserDetailRepository].
 *
 * Covers the happy path, 401-refresh-and-retry flow, refresh-token logout, and error handling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UserDetailRepositoryTest {

    private val api: IUserDetailAPI = mockk()
    private val applicationSettingApi: IApplicationSettingInterface = mockk()
    private val preferenceHelper: PreferenceHelper = mockk(relaxed = true)
    private val authEventBus: AuthEventBus = mockk(relaxed = true)
    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var repository: UserDetailRepository

    @Before
    fun setup() {
        repository = UserDetailRepository(api, applicationSettingApi, preferenceHelper, testDispatcher, authEventBus)
    }

    private fun successApiResponse(data: UserDetail? = null): Response<ApiResponse<UserDetail>> =
        Response.success(
            ApiResponse(status = 200, isSuccess = true, message = "OK", token = null, data = data)
        )

    private fun errorResponse(code: Int): Response<ApiResponse<UserDetail>> =
        Response.error(code, "".toResponseBody(null))

    // UD_REPO_001
    @Test
    fun `getUserDetail returns success when API responds with 200`() = runTest {
        coEvery { api.getUserDetail(any(), any()) } returns successApiResponse()

        val result = repository.getUserDetail("access-tok")

        assertTrue(result.isSuccess)
        assertNotNull(result.getOrNull())
    }

    // UD_REPO_002 — 401 → token refresh succeeds → retry returns 200
    @Test
    fun `getUserDetail refreshes token on 401 retries and returns success`() = runTest {
        val refreshBody = RefreshTokenResponse(accessToken = "new-access", refreshToken = "new-refresh")
        every { preferenceHelper.getRefreshToken() } returns "old-refresh"
        every { preferenceHelper.getAccessToken() } returns "new-access"
        coEvery { api.getUserDetail(any(), any()) } returnsMany listOf(
            errorResponse(401),
            successApiResponse()
        )
        coEvery {
            applicationSettingApi.refreshToken(RefreshTokenRequest("old-refresh"))
        } returns Response.success(refreshBody)

        val result = repository.getUserDetail("old-access")

        assertTrue(result.isSuccess)
        verify { preferenceHelper.saveTokens("new-access", "new-refresh") }
    }

    // UD_REPO_003 — 401 → refresh token itself returns 401 → LOGOUT
    @Test
    fun `getUserDetail returns LOGOUT failure and clears session when refresh token is rejected`() = runTest {
        every { preferenceHelper.getRefreshToken() } returns "expired-refresh"
        coEvery { api.getUserDetail(any(), any()) } returns errorResponse(401)
        coEvery {
            applicationSettingApi.refreshToken(RefreshTokenRequest("expired-refresh"))
        } returns Response.error(401, "".toResponseBody(null))

        val result = repository.getUserDetail("old-access")

        assertTrue(result.isFailure)
        assertEquals("LOGOUT", result.exceptionOrNull()?.message)
        verify { preferenceHelper.clearTokens() }
        verify { preferenceHelper.setUserLoggedIn(false) }
    }

    // UD_REPO_004 — 401 but no refresh token stored → immediate failure
    @Test
    fun `getUserDetail returns failure when no refresh token is available`() = runTest {
        every { preferenceHelper.getRefreshToken() } returns null
        coEvery { api.getUserDetail(any(), any()) } returns errorResponse(401)

        val result = repository.getUserDetail("old-access")

        assertTrue(result.isFailure)
        assertEquals("No refresh token available", result.exceptionOrNull()?.message)
    }

    // UD_REPO_005
    @Test
    fun `getUserDetail returns failure for server error 500`() = runTest {
        coEvery { api.getUserDetail(any(), any()) } returns errorResponse(500)

        val result = repository.getUserDetail("access-tok")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("500") == true)
    }

    // UD_REPO_006
    @Test
    fun `getUserDetail returns failure when IOException is thrown`() = runTest {
        coEvery { api.getUserDetail(any(), any()) } throws IOException("connection refused")

        val result = repository.getUserDetail("access-tok")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
    }
}
