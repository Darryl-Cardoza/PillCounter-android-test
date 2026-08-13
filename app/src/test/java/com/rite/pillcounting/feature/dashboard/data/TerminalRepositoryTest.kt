package com.rite.pillcounting.feature.dashboard.data

import android.util.Log
import com.rite.pillcounting.core.refreshToken.domain.model.RefreshTokenRequest
import com.rite.pillcounting.core.refreshToken.domain.model.RefreshTokenResponse
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.dashboard.data.remote.ITerminalApi
import com.rite.pillcounting.feature.dashboard.domain.model.TerminalUpdateRequest
import com.rite.pillcounting.feature.dashboard.domain.model.TerminalUpdateResponse
import com.rite.pillcounting.feature.settings.data.remote.IApplicationSettingInterface
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalRepositoryTest {

    private lateinit var terminalApi: ITerminalApi
    private lateinit var preferenceHelper: PreferenceHelper
    private lateinit var applicationSettingApi: IApplicationSettingInterface
    private lateinit var repository: TerminalRepository

    private val terminalId = "terminal-123"
    private val request = TerminalUpdateRequest(terminalName = "Front Desk", isActive = true, deviceKey = "device-key")
    private val successResponse = TerminalUpdateResponse(message = "ok", isSuccess = true)

    @Before
    fun setup() {
        // AppLogger wraps android.util.Log, which is not available on the JVM.
        mockkStatic(Log::class)
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.i(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        terminalApi = mockk()
        preferenceHelper = mockk(relaxed = true)
        applicationSettingApi = mockk()

        repository = TerminalRepository(
            terminalApi = terminalApi,
            ioDispatcher = UnconfinedTestDispatcher(),
            preferenceHelper = preferenceHelper,
            applicationSettingApi = applicationSettingApi
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun httpException(code: Int): HttpException {
        val body = "error".toResponseBody("text/plain".toMediaTypeOrNull())
        return HttpException(Response.error<Any>(code, body))
    }

    // ────────────────────────────── updateTerminal happy path ──────────────────────────────

    @Test
    fun `updateTerminal returns success and sends bearer token`() = runTest {
        every { preferenceHelper.getAccessToken() } returns "abc"
        coEvery { terminalApi.updateTerminal("Bearer abc", terminalId, request) } returns successResponse

        val result = repository.updateTerminal(terminalId, request)

        assertTrue(result.isSuccess)
        assertEquals(successResponse, result.getOrNull())
        coVerify(exactly = 1) { terminalApi.updateTerminal("Bearer abc", terminalId, request) }
    }

    @Test
    fun `updateTerminal sends empty bearer when no access token`() = runTest {
        every { preferenceHelper.getAccessToken() } returns null
        coEvery { terminalApi.updateTerminal("Bearer ", terminalId, request) } returns successResponse

        val result = repository.updateTerminal(terminalId, request)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { terminalApi.updateTerminal("Bearer ", terminalId, request) }
    }

    // ────────────────────────────── non-401 / generic failures ──────────────────────────────

    @Test
    fun `updateTerminal returns failure on non-401 HttpException`() = runTest {
        val exception = httpException(500)
        every { preferenceHelper.getAccessToken() } returns "abc"
        coEvery { terminalApi.updateTerminal(any(), any(), any()) } throws exception

        val result = repository.updateTerminal(terminalId, request)

        assertTrue(result.isFailure)
        assertSame(exception, result.exceptionOrNull())
        coVerify(exactly = 0) { applicationSettingApi.refreshToken(any()) }
    }

    @Test
    fun `updateTerminal returns failure on generic exception`() = runTest {
        val exception = RuntimeException("boom")
        every { preferenceHelper.getAccessToken() } returns "abc"
        coEvery { terminalApi.updateTerminal(any(), any(), any()) } throws exception

        val result = repository.updateTerminal(terminalId, request)

        assertTrue(result.isFailure)
        assertSame(exception, result.exceptionOrNull())
    }

    // ────────────────────────────── 401 → token refresh & retry ──────────────────────────────

    @Test
    fun `updateTerminal refreshes token on 401 and retries successfully`() = runTest {
        every { preferenceHelper.getAccessToken() } returnsMany listOf("oldToken", "newToken")
        every { preferenceHelper.getRefreshToken() } returns "refresh"
        coEvery { terminalApi.updateTerminal("Bearer oldToken", terminalId, request) } throws httpException(401)
        coEvery { terminalApi.updateTerminal("Bearer newToken", terminalId, request) } returns successResponse
        coEvery { applicationSettingApi.refreshToken(RefreshTokenRequest("refresh")) } returns
            Response.success(RefreshTokenResponse(accessToken = "newToken", refreshToken = "newRefresh"))

        val result = repository.updateTerminal(terminalId, request)

        assertTrue(result.isSuccess)
        assertEquals(successResponse, result.getOrNull())
        verify(exactly = 1) { preferenceHelper.saveTokens("newToken", "newRefresh") }
        coVerify(exactly = 1) { terminalApi.updateTerminal("Bearer newToken", terminalId, request) }
    }

    @Test
    fun `updateTerminal keeps old refresh token when refresh response omits it`() = runTest {
        every { preferenceHelper.getAccessToken() } returnsMany listOf("oldToken", "newToken")
        every { preferenceHelper.getRefreshToken() } returns "refresh"
        coEvery { terminalApi.updateTerminal("Bearer oldToken", terminalId, request) } throws httpException(401)
        coEvery { terminalApi.updateTerminal("Bearer newToken", terminalId, request) } returns successResponse
        coEvery { applicationSettingApi.refreshToken(any()) } returns
            Response.success(RefreshTokenResponse(accessToken = "newToken", refreshToken = null))

        val result = repository.updateTerminal(terminalId, request)

        assertTrue(result.isSuccess)
        verify(exactly = 1) { preferenceHelper.saveTokens("newToken", "refresh") }
    }

    @Test
    fun `updateTerminal fails on 401 when no refresh token available`() = runTest {
        every { preferenceHelper.getAccessToken() } returns "oldToken"
        every { preferenceHelper.getRefreshToken() } returns null
        coEvery { terminalApi.updateTerminal(any(), any(), any()) } throws httpException(401)

        val result = repository.updateTerminal(terminalId, request)

        assertTrue(result.isFailure)
        assertEquals("No refresh token available", result.exceptionOrNull()?.message)
        coVerify(exactly = 0) { applicationSettingApi.refreshToken(any()) }
    }

    @Test
    fun `updateTerminal fails on 401 when refresh returns blank access token`() = runTest {
        every { preferenceHelper.getAccessToken() } returns "oldToken"
        every { preferenceHelper.getRefreshToken() } returns "refresh"
        coEvery { terminalApi.updateTerminal(any(), any(), any()) } throws httpException(401)
        coEvery { applicationSettingApi.refreshToken(any()) } returns
            Response.success(RefreshTokenResponse(accessToken = null, message = "expired"))

        val result = repository.updateTerminal(terminalId, request)

        assertTrue(result.isFailure)
        assertEquals("Failed to refresh token: expired", result.exceptionOrNull()?.message)
        verify(exactly = 0) { preferenceHelper.saveTokens(any(), any()) }
    }

    @Test
    fun `updateTerminal fails on 401 when refresh call throws`() = runTest {
        val refreshError = RuntimeException("network down")
        every { preferenceHelper.getAccessToken() } returns "oldToken"
        every { preferenceHelper.getRefreshToken() } returns "refresh"
        coEvery { terminalApi.updateTerminal(any(), any(), any()) } throws httpException(401)
        coEvery { applicationSettingApi.refreshToken(any()) } throws refreshError

        val result = repository.updateTerminal(terminalId, request)

        assertTrue(result.isFailure)
        assertSame(refreshError, result.exceptionOrNull())
    }

    @Test
    fun `updateTerminal fails on 401 when retry throws after successful refresh`() = runTest {
        val retryError = RuntimeException("retry failed")
        every { preferenceHelper.getAccessToken() } returnsMany listOf("oldToken", "newToken")
        every { preferenceHelper.getRefreshToken() } returns "refresh"
        coEvery { terminalApi.updateTerminal("Bearer oldToken", terminalId, request) } throws httpException(401)
        coEvery { terminalApi.updateTerminal("Bearer newToken", terminalId, request) } throws retryError
        coEvery { applicationSettingApi.refreshToken(any()) } returns
            Response.success(RefreshTokenResponse(accessToken = "newToken", refreshToken = "newRefresh"))

        val result = repository.updateTerminal(terminalId, request)

        assertTrue(result.isFailure)
        assertSame(retryError, result.exceptionOrNull())
        verify(exactly = 1) { preferenceHelper.saveTokens("newToken", "newRefresh") }
    }
}
