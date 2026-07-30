package com.rite.pillcounting.feature.profile.data

import android.util.Log
import com.rite.pillcounting.core.models.ApiResponse
import com.rite.pillcounting.core.refreshToken.domain.model.RefreshTokenRequest
import com.rite.pillcounting.core.refreshToken.domain.model.RefreshTokenResponse
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.profile.data.remote.IProfileApi
import com.rite.pillcounting.feature.profile.domain.model.Country
import com.rite.pillcounting.feature.profile.domain.model.CountriesData
import com.rite.pillcounting.feature.profile.domain.model.ProfileDeleteResponse
import com.rite.pillcounting.feature.profile.domain.model.ProfileUpdateRequest
import com.rite.pillcounting.feature.profile.domain.model.ProfileUpdateResponse
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
class ProfileRepositoryTest {

    private lateinit var profileApi: IProfileApi
    private lateinit var preferenceHelper: PreferenceHelper
    private lateinit var applicationSettingApi: IApplicationSettingInterface
    private lateinit var repository: ProfileRepository

    private val request = ProfileUpdateRequest(
        fName = "John",
        lName = "Doe",
        pharmacyName = "Rite Pharmacy",
        phoneNumber = "1234567890",
        npiId = "npi-1",
        isProfileComplete = true,
        avatarUrl = "http://avatar",
        notificationsEnabled = true,
        language = "en",
        timezone = "UTC"
    )
    private val updateResponse = ProfileUpdateResponse(status = 200, message = "ok", isSuccess = true)
    private val deleteResponse = ProfileDeleteResponse(status = 200, message = "deleted", isSuccess = true)

    @Before
    fun setup() {
        // AppLogger wraps android.util.Log, which is not available on the JVM.
        mockkStatic(Log::class)
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.i(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        profileApi = mockk()
        preferenceHelper = mockk(relaxed = true)
        applicationSettingApi = mockk()

        repository = ProfileRepository(
            profileApi = profileApi,
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

    // ══════════════════════════════════ updateProfile ══════════════════════════════════

    // ────────────────────────────── happy path ──────────────────────────────

    @Test
    fun `updateProfile returns success and sends bearer token`() = runTest {
        every { preferenceHelper.getAccessToken() } returns "abc"
        coEvery { profileApi.updateProfile("Bearer abc", request) } returns updateResponse

        val result = repository.updateProfile(request)

        assertTrue(result.isSuccess)
        assertEquals(updateResponse, result.getOrNull())
        coVerify(exactly = 1) { profileApi.updateProfile("Bearer abc", request) }
    }

    @Test
    fun `updateProfile sends empty bearer when no access token`() = runTest {
        every { preferenceHelper.getAccessToken() } returns null
        coEvery { profileApi.updateProfile("Bearer ", request) } returns updateResponse

        val result = repository.updateProfile(request)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { profileApi.updateProfile("Bearer ", request) }
    }

    // ────────────────────────────── non-401 / generic failures ──────────────────────────────

    @Test
    fun `updateProfile returns failure on non-401 HttpException`() = runTest {
        val exception = httpException(500)
        every { preferenceHelper.getAccessToken() } returns "abc"
        coEvery { profileApi.updateProfile(any(), any()) } throws exception

        val result = repository.updateProfile(request)

        assertTrue(result.isFailure)
        assertSame(exception, result.exceptionOrNull())
        coVerify(exactly = 0) { applicationSettingApi.refreshToken(any()) }
    }

    @Test
    fun `updateProfile returns failure on generic exception`() = runTest {
        val exception = RuntimeException("boom")
        every { preferenceHelper.getAccessToken() } returns "abc"
        coEvery { profileApi.updateProfile(any(), any()) } throws exception

        val result = repository.updateProfile(request)

        assertTrue(result.isFailure)
        assertSame(exception, result.exceptionOrNull())
    }

    // ────────────────────────────── 401 → token refresh & retry ──────────────────────────────

    @Test
    fun `updateProfile refreshes token on 401 and retries successfully`() = runTest {
        every { preferenceHelper.getAccessToken() } returnsMany listOf("oldToken", "newToken")
        every { preferenceHelper.getRefreshToken() } returns "refresh"
        coEvery { profileApi.updateProfile("Bearer oldToken", request) } throws httpException(401)
        coEvery { profileApi.updateProfile("Bearer newToken", request) } returns updateResponse
        coEvery { applicationSettingApi.refreshToken(RefreshTokenRequest("refresh")) } returns
            Response.success(RefreshTokenResponse(accessToken = "newToken", refreshToken = "newRefresh"))

        val result = repository.updateProfile(request)

        assertTrue(result.isSuccess)
        assertEquals(updateResponse, result.getOrNull())
        verify(exactly = 1) { preferenceHelper.saveTokens("newToken", "newRefresh") }
        coVerify(exactly = 1) { profileApi.updateProfile("Bearer newToken", request) }
    }

    @Test
    fun `updateProfile keeps old refresh token when refresh response omits it`() = runTest {
        every { preferenceHelper.getAccessToken() } returnsMany listOf("oldToken", "newToken")
        every { preferenceHelper.getRefreshToken() } returns "refresh"
        coEvery { profileApi.updateProfile("Bearer oldToken", request) } throws httpException(401)
        coEvery { profileApi.updateProfile("Bearer newToken", request) } returns updateResponse
        coEvery { applicationSettingApi.refreshToken(any()) } returns
            Response.success(RefreshTokenResponse(accessToken = "newToken", refreshToken = null))

        val result = repository.updateProfile(request)

        assertTrue(result.isSuccess)
        verify(exactly = 1) { preferenceHelper.saveTokens("newToken", "refresh") }
    }

    @Test
    fun `updateProfile fails on 401 when no refresh token available`() = runTest {
        every { preferenceHelper.getAccessToken() } returns "oldToken"
        every { preferenceHelper.getRefreshToken() } returns null
        coEvery { profileApi.updateProfile(any(), any()) } throws httpException(401)

        val result = repository.updateProfile(request)

        assertTrue(result.isFailure)
        assertEquals("No refresh token available", result.exceptionOrNull()?.message)
        coVerify(exactly = 0) { applicationSettingApi.refreshToken(any()) }
    }

    @Test
    fun `updateProfile fails on 401 when refresh returns blank access token`() = runTest {
        every { preferenceHelper.getAccessToken() } returns "oldToken"
        every { preferenceHelper.getRefreshToken() } returns "refresh"
        coEvery { profileApi.updateProfile(any(), any()) } throws httpException(401)
        coEvery { applicationSettingApi.refreshToken(any()) } returns
            Response.success(RefreshTokenResponse(accessToken = null, message = "expired"))

        val result = repository.updateProfile(request)

        assertTrue(result.isFailure)
        assertEquals("Failed to refresh token: expired", result.exceptionOrNull()?.message)
        verify(exactly = 0) { preferenceHelper.saveTokens(any(), any()) }
    }

    @Test
    fun `updateProfile fails on 401 when refresh call throws`() = runTest {
        val refreshError = RuntimeException("network down")
        every { preferenceHelper.getAccessToken() } returns "oldToken"
        every { preferenceHelper.getRefreshToken() } returns "refresh"
        coEvery { profileApi.updateProfile(any(), any()) } throws httpException(401)
        coEvery { applicationSettingApi.refreshToken(any()) } throws refreshError

        val result = repository.updateProfile(request)

        assertTrue(result.isFailure)
        assertSame(refreshError, result.exceptionOrNull())
    }

    @Test
    fun `updateProfile fails on 401 when retry throws after successful refresh`() = runTest {
        val retryError = RuntimeException("retry failed")
        every { preferenceHelper.getAccessToken() } returnsMany listOf("oldToken", "newToken")
        every { preferenceHelper.getRefreshToken() } returns "refresh"
        coEvery { profileApi.updateProfile("Bearer oldToken", request) } throws httpException(401)
        coEvery { profileApi.updateProfile("Bearer newToken", request) } throws retryError
        coEvery { applicationSettingApi.refreshToken(any()) } returns
            Response.success(RefreshTokenResponse(accessToken = "newToken", refreshToken = "newRefresh"))

        val result = repository.updateProfile(request)

        assertTrue(result.isFailure)
        assertSame(retryError, result.exceptionOrNull())
        verify(exactly = 1) { preferenceHelper.saveTokens("newToken", "newRefresh") }
    }

    // ══════════════════════════════════ deleteProfile ══════════════════════════════════

    // ────────────────────────────── happy path ──────────────────────────────

    @Test
    fun `deleteProfile returns success and sends bearer token`() = runTest {
        every { preferenceHelper.getAccessToken() } returns "abc"
        coEvery { profileApi.deleteProfile("Bearer abc") } returns deleteResponse

        val result = repository.deleteProfile()

        assertTrue(result.isSuccess)
        assertEquals(deleteResponse, result.getOrNull())
        coVerify(exactly = 1) { profileApi.deleteProfile("Bearer abc") }
    }

    @Test
    fun `deleteProfile sends empty bearer when no access token`() = runTest {
        every { preferenceHelper.getAccessToken() } returns null
        coEvery { profileApi.deleteProfile("Bearer ") } returns deleteResponse

        val result = repository.deleteProfile()

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { profileApi.deleteProfile("Bearer ") }
    }

    // ────────────────────────────── non-401 / generic failures ──────────────────────────────

    @Test
    fun `deleteProfile returns failure on non-401 HttpException`() = runTest {
        val exception = httpException(500)
        every { preferenceHelper.getAccessToken() } returns "abc"
        coEvery { profileApi.deleteProfile(any()) } throws exception

        val result = repository.deleteProfile()

        assertTrue(result.isFailure)
        assertSame(exception, result.exceptionOrNull())
        coVerify(exactly = 0) { applicationSettingApi.refreshToken(any()) }
    }

    @Test
    fun `deleteProfile returns failure on generic exception`() = runTest {
        val exception = RuntimeException("boom")
        every { preferenceHelper.getAccessToken() } returns "abc"
        coEvery { profileApi.deleteProfile(any()) } throws exception

        val result = repository.deleteProfile()

        assertTrue(result.isFailure)
        assertSame(exception, result.exceptionOrNull())
    }

    // ────────────────────────────── 401 → token refresh & retry ──────────────────────────────

    @Test
    fun `deleteProfile refreshes token on 401 and retries successfully`() = runTest {
        every { preferenceHelper.getAccessToken() } returnsMany listOf("oldToken", "newToken")
        every { preferenceHelper.getRefreshToken() } returns "refresh"
        coEvery { profileApi.deleteProfile("Bearer oldToken") } throws httpException(401)
        coEvery { profileApi.deleteProfile("Bearer newToken") } returns deleteResponse
        coEvery { applicationSettingApi.refreshToken(RefreshTokenRequest("refresh")) } returns
            Response.success(RefreshTokenResponse(accessToken = "newToken", refreshToken = "newRefresh"))

        val result = repository.deleteProfile()

        assertTrue(result.isSuccess)
        assertEquals(deleteResponse, result.getOrNull())
        verify(exactly = 1) { preferenceHelper.saveTokens("newToken", "newRefresh") }
        coVerify(exactly = 1) { profileApi.deleteProfile("Bearer newToken") }
    }

    @Test
    fun `deleteProfile keeps old refresh token when refresh response omits it`() = runTest {
        every { preferenceHelper.getAccessToken() } returnsMany listOf("oldToken", "newToken")
        every { preferenceHelper.getRefreshToken() } returns "refresh"
        coEvery { profileApi.deleteProfile("Bearer oldToken") } throws httpException(401)
        coEvery { profileApi.deleteProfile("Bearer newToken") } returns deleteResponse
        coEvery { applicationSettingApi.refreshToken(any()) } returns
            Response.success(RefreshTokenResponse(accessToken = "newToken", refreshToken = null))

        val result = repository.deleteProfile()

        assertTrue(result.isSuccess)
        verify(exactly = 1) { preferenceHelper.saveTokens("newToken", "refresh") }
    }

    @Test
    fun `deleteProfile fails on 401 when no refresh token available`() = runTest {
        every { preferenceHelper.getAccessToken() } returns "oldToken"
        every { preferenceHelper.getRefreshToken() } returns null
        coEvery { profileApi.deleteProfile(any()) } throws httpException(401)

        val result = repository.deleteProfile()

        assertTrue(result.isFailure)
        assertEquals("No refresh token available", result.exceptionOrNull()?.message)
        coVerify(exactly = 0) { applicationSettingApi.refreshToken(any()) }
    }

    @Test
    fun `deleteProfile fails on 401 when refresh returns blank access token`() = runTest {
        every { preferenceHelper.getAccessToken() } returns "oldToken"
        every { preferenceHelper.getRefreshToken() } returns "refresh"
        coEvery { profileApi.deleteProfile(any()) } throws httpException(401)
        coEvery { applicationSettingApi.refreshToken(any()) } returns
            Response.success(RefreshTokenResponse(accessToken = null, message = "expired"))

        val result = repository.deleteProfile()

        assertTrue(result.isFailure)
        assertEquals("Failed to refresh token: expired", result.exceptionOrNull()?.message)
        verify(exactly = 0) { preferenceHelper.saveTokens(any(), any()) }
    }

    @Test
    fun `deleteProfile fails on 401 when refresh call throws`() = runTest {
        val refreshError = RuntimeException("network down")
        every { preferenceHelper.getAccessToken() } returns "oldToken"
        every { preferenceHelper.getRefreshToken() } returns "refresh"
        coEvery { profileApi.deleteProfile(any()) } throws httpException(401)
        coEvery { applicationSettingApi.refreshToken(any()) } throws refreshError

        val result = repository.deleteProfile()

        assertTrue(result.isFailure)
        assertSame(refreshError, result.exceptionOrNull())
    }

    @Test
    fun `deleteProfile fails on 401 when retry throws after successful refresh`() = runTest {
        val retryError = RuntimeException("retry failed")
        every { preferenceHelper.getAccessToken() } returnsMany listOf("oldToken", "newToken")
        every { preferenceHelper.getRefreshToken() } returns "refresh"
        coEvery { profileApi.deleteProfile("Bearer oldToken") } throws httpException(401)
        coEvery { profileApi.deleteProfile("Bearer newToken") } throws retryError
        coEvery { applicationSettingApi.refreshToken(any()) } returns
            Response.success(RefreshTokenResponse(accessToken = "newToken", refreshToken = "newRefresh"))

        val result = repository.deleteProfile()

        assertTrue(result.isFailure)
        assertSame(retryError, result.exceptionOrNull())
        verify(exactly = 1) { preferenceHelper.saveTokens("newToken", "newRefresh") }
    }

    // ══════════════════════════════════ getCountries ══════════════════════════════════

    @Test
    fun `getCountries returns the countries list on success`() = runTest {
        val countries = listOf(Country(code = "US", name = "United States"))
        coEvery { profileApi.getCountries() } returns
            ApiResponse(200, true, "ok", null, CountriesData(countries))

        val result = repository.getCountries()

        assertTrue(result.isSuccess)
        assertEquals(countries, result.getOrNull())
    }

    @Test
    fun `getCountries returns empty list when data is null`() = runTest {
        coEvery { profileApi.getCountries() } returns ApiResponse(200, true, "ok", null, null)

        val result = repository.getCountries()

        assertTrue(result.isSuccess)
        assertEquals(emptyList<Country>(), result.getOrNull())
    }

    @Test
    fun `getCountries returns empty list when countries field is null`() = runTest {
        coEvery { profileApi.getCountries() } returns
            ApiResponse(200, true, "ok", null, CountriesData(null))

        val result = repository.getCountries()

        assertTrue(result.isSuccess)
        assertEquals(emptyList<Country>(), result.getOrNull())
    }

    @Test
    fun `getCountries returns failure on exception`() = runTest {
        val exception = RuntimeException("boom")
        coEvery { profileApi.getCountries() } throws exception

        val result = repository.getCountries()

        assertTrue(result.isFailure)
        assertSame(exception, result.exceptionOrNull())
    }
}
