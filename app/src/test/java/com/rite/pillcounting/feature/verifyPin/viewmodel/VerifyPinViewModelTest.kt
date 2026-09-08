package com.rite.pillcounting.feature.verifyPin.viewmodel

import android.content.Context
import android.util.Log
import app.cash.turbine.test
import com.rite.pillcounting.R
import com.rite.pillcounting.core.health.logic.SessionHealthController
import com.rite.pillcounting.core.utils.common.NetworkUtils
import com.rite.pillcounting.core.utils.device.DeviceKeyProvider
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.otp.data.VerifyPinRepository
import com.rite.pillcounting.feature.verifyPin.domain.model.VerifiedUser
import com.rite.pillcounting.feature.verifyPin.domain.model.VerifyPinData
import com.rite.pillcounting.feature.verifyPin.domain.model.VerifyPinResponse
import com.rite.pillcounting.feature.verifyPin.domain.model.VerifyPinUiState
import com.rite.pillcounting.feature.verifyPin.presentation.viewmodel.VerifyPinViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/**
 * Unit tests for [VerifyPinViewModel].
 *
 * Targets 100% line + method coverage including every branch of the private
 * mapExceptionToUserMessage(...) reached through verifyPin() onFailure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VerifyPinViewModelTest {

    private lateinit var repository: VerifyPinRepository
    private lateinit var context: Context
    private lateinit var prefs: PreferenceHelper
    private lateinit var deviceKeyProvider: DeviceKeyProvider
    private lateinit var sessionHealthController: SessionHealthController
    private lateinit var viewModel: VerifyPinViewModel

    private val email = "user@test.com"
    private val otp = "123456"
    private val testDeviceKey = "test-device-key"

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.i(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        Dispatchers.setMain(StandardTestDispatcher())

        mockkObject(NetworkUtils)
        every { NetworkUtils.isNetworkAvailable(any()) } returns true

        repository = mockk()
        context = mockk()
        prefs = mockk(relaxed = true)
        deviceKeyProvider = mockk(relaxed = true)
        sessionHealthController = mockk(relaxed = true)
        coEvery { deviceKeyProvider.getDeviceKey() } returns testDeviceKey

        every { context.getString(any()) } returns "msg"

        viewModel = VerifyPinViewModel(repository, context, prefs, deviceKeyProvider, sessionHealthController)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun httpException(code: Int, jsonBody: String): HttpException {
        val body = jsonBody.toResponseBody("application/json".toMediaTypeOrNull())
        return HttpException(Response.error<Any>(code, body))
    }

    private val validErrorJson =
        """{"status":400,"is_success":false,"message":"parsed-msg","token":null,"data":{}}"""

    private val invalidJson = "<<not-json>>"

    // ───────────────────────────── otp length guard ─────────────────────────────

    @Test
    fun `verifyPin with otp length not 6 sets Error and does not call repository`() = runTest {
        every { context.getString(R.string.error_invalid_otp) } returns "invalid-otp"

        viewModel.verifyPin(email, "123")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is VerifyPinUiState.Error)
        assertEquals("invalid-otp", (state as VerifyPinUiState.Error).message)
        coVerify(exactly = 0) { repository.verifyPin(any(), any(), any(), any()) }
    }

    // ───────────────────────────── already-loading guard ─────────────────────────────

    @Test
    fun `verifyPin second call dropped while first is in Loading`() = runTest {
        // Suspend the first repository call so the VM stays in Loading, then issue a
        // second call which must hit the already-loading guard and return early.
        val gate = CompletableDeferred<Result<VerifyPinResponse>>()
        coEvery { repository.verifyPin(email, otp, testDeviceKey, any()) } coAnswers { gate.await() }

        viewModel.verifyPin(email, otp) // schedules coroutine #1
        advanceUntilIdle()              // coroutine #1 runs: sets Loading, suspends on gate
        assertEquals(VerifyPinUiState.Loading, viewModel.uiState.value)

        viewModel.verifyPin(email, otp) // state is Loading -> guard hit, returns early
        advanceUntilIdle()

        // Release the first call so the test coroutine can finish cleanly.
        gate.complete(Result.success(VerifyPinResponse(data = VerifyPinData())))
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.verifyPin(email, otp, testDeviceKey, any()) }
    }

    // ───────────────────────────── no internet ─────────────────────────────

    @Test
    fun `verifyPin with no internet sets Error and skips repository`() = runTest {
        every { NetworkUtils.isNetworkAvailable(any()) } returns false
        every { context.getString(R.string.error_no_internet) } returns "no-internet"

        viewModel.verifyPin(email, otp)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is VerifyPinUiState.Error)
        assertEquals("no-internet", (state as VerifyPinUiState.Error).message)
        coVerify(exactly = 0) { repository.verifyPin(any(), any(), any(), any()) }
    }

    // ───────────────────────────── success branches ─────────────────────────────

    @Test
    fun `verifyPin success with both tokens and non-null user saves tokens`() = runTest {
        val user = VerifiedUser(email = email, isVerified = true)
        val data = VerifyPinData(accessToken = "access", refreshToken = "refresh", user = user)
        coEvery { repository.verifyPin(email, otp, testDeviceKey, any()) } returns
            Result.success(VerifyPinResponse(status = 200, message = "ok", data = data))

        viewModel.uiState.test {
            assertEquals(VerifyPinUiState.Idle, awaitItem())
            viewModel.verifyPin(email, otp)
            assertEquals(VerifyPinUiState.Loading, awaitItem())
            assertEquals(VerifyPinUiState.Success, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        advanceUntilIdle()

        verify(exactly = 1) { prefs.saveTokens("access", "refresh") }
    }

    @Test
    fun `verifyPin success persists the verified email`() = runTest {
        val user = VerifiedUser(email = email, isVerified = true)
        val data = VerifyPinData(accessToken = "access", refreshToken = "refresh", user = user)
        coEvery { repository.verifyPin(email, otp, testDeviceKey, any()) } returns
            Result.success(VerifyPinResponse(status = 200, message = "ok", data = data))

        viewModel.verifyPin(email, otp)
        advanceUntilIdle()

        verify(exactly = 1) { prefs.setLoggedInEmail(email) }
    }

    @Test
    fun `verifyPin success with missing token and null user does not save tokens`() = runTest {
        // accessToken null -> warn branch; user null -> warn branch
        val data = VerifyPinData(accessToken = null, refreshToken = null, user = null)
        coEvery { repository.verifyPin(email, otp, testDeviceKey, any()) } returns
            Result.success(VerifyPinResponse(status = 200, data = data))

        viewModel.verifyPin(email, otp)
        advanceUntilIdle()

        assertEquals(VerifyPinUiState.Success, viewModel.uiState.value)
        coVerify(exactly = 0) { prefs.saveTokens(any(), any()) }
    }

    @Test
    fun `verifyPin success with blank refresh token hits missing-token branch`() = runTest {
        val user = VerifiedUser(email = email)
        val data = VerifyPinData(accessToken = "access", refreshToken = "", user = user)
        coEvery { repository.verifyPin(email, otp, testDeviceKey, any()) } returns
            Result.success(VerifyPinResponse(data = data))

        viewModel.verifyPin(email, otp)
        advanceUntilIdle()

        assertEquals(VerifyPinUiState.Success, viewModel.uiState.value)
        coVerify(exactly = 0) { prefs.saveTokens(any(), any()) }
    }

    @Test
    fun `verifyPin success with null data treats tokens and user as missing`() = runTest {
        coEvery { repository.verifyPin(email, otp, testDeviceKey, any()) } returns
            Result.success(VerifyPinResponse(status = 200, data = null))

        viewModel.verifyPin(email, otp)
        advanceUntilIdle()

        assertEquals(VerifyPinUiState.Success, viewModel.uiState.value)
        coVerify(exactly = 0) { prefs.saveTokens(any(), any()) }
    }

    // ───────────────────────────── device key fetch failure ─────────────────────────────

    @Test
    fun `verifyPin sets Error when device key unavailable`() = runTest {
        coEvery { deviceKeyProvider.getDeviceKey() } returns null
        every { context.getString(R.string.error_server_unavailable) } returns "server-unavailable"

        viewModel.verifyPin(email, otp)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is VerifyPinUiState.Error)
        assertEquals("server-unavailable", (state as VerifyPinUiState.Error).message)
        coVerify(exactly = 0) { repository.verifyPin(any(), any(), any(), any()) }
    }

    // ─────────────── failure: mapExceptionToUserMessage branches ───────────────

    @Test
    fun `verifyPin failure IOException maps to server unavailable`() = runTest {
        coEvery { repository.verifyPin(email, otp, testDeviceKey, any()) } returns
            Result.failure(IOException("conn reset"))
        every { context.getString(R.string.error_server_unavailable) } returns "server-unavailable"

        viewModel.verifyPin(email, otp)
        advanceUntilIdle()

        assertEquals("server-unavailable", (viewModel.uiState.value as VerifyPinUiState.Error).message)
    }

    @Test
    fun `verifyPin failure HttpException 401 maps to invalid otp`() = runTest {
        coEvery { repository.verifyPin(email, otp, testDeviceKey, any()) } returns
            Result.failure(httpException(401, invalidJson))
        every { context.getString(R.string.error_invalid_otp) } returns "invalid-otp"

        viewModel.verifyPin(email, otp)
        advanceUntilIdle()

        assertEquals("invalid-otp", (viewModel.uiState.value as VerifyPinUiState.Error).message)
    }

    @Test
    fun `verifyPin failure HttpException 400 with parsed message`() = runTest {
        coEvery { repository.verifyPin(email, otp, testDeviceKey, any()) } returns
            Result.failure(httpException(400, validErrorJson))

        viewModel.verifyPin(email, otp)
        advanceUntilIdle()

        assertEquals("parsed-msg", (viewModel.uiState.value as VerifyPinUiState.Error).message)
    }

    @Test
    fun `verifyPin failure HttpException 400 with unparseable body falls back to invalid otp`() =
        runTest {
            coEvery { repository.verifyPin(email, otp, testDeviceKey, any()) } returns
                Result.failure(httpException(400, invalidJson))
            every { context.getString(R.string.error_invalid_otp) } returns "invalid-otp"

            viewModel.verifyPin(email, otp)
            advanceUntilIdle()

            assertEquals("invalid-otp", (viewModel.uiState.value as VerifyPinUiState.Error).message)
        }

    @Test
    fun `verifyPin failure HttpException 5xx maps to server down`() = runTest {
        coEvery { repository.verifyPin(email, otp, testDeviceKey, any()) } returns
            Result.failure(httpException(503, invalidJson))
        every { context.getString(R.string.error_server_down) } returns "server-down"

        viewModel.verifyPin(email, otp)
        advanceUntilIdle()

        assertEquals("server-down", (viewModel.uiState.value as VerifyPinUiState.Error).message)
    }

    @Test
    fun `verifyPin failure HttpException else-code with parsed message`() = runTest {
        coEvery { repository.verifyPin(email, otp, testDeviceKey, any()) } returns
            Result.failure(httpException(418, validErrorJson))

        viewModel.verifyPin(email, otp)
        advanceUntilIdle()

        assertEquals("parsed-msg", (viewModel.uiState.value as VerifyPinUiState.Error).message)
    }

    @Test
    fun `verifyPin failure HttpException else-code with null parsed message falls back to unknown`() =
        runTest {
            coEvery { repository.verifyPin(email, otp, testDeviceKey, any()) } returns
                Result.failure(httpException(418, invalidJson))
            every { context.getString(R.string.error_unknown) } returns "unknown"

            viewModel.verifyPin(email, otp)
            advanceUntilIdle()

            assertEquals("unknown", (viewModel.uiState.value as VerifyPinUiState.Error).message)
        }

    @Test
    fun `verifyPin failure generic exception maps to unknown`() = runTest {
        coEvery { repository.verifyPin(email, otp, testDeviceKey, any()) } returns
            Result.failure(RuntimeException("boom"))
        every { context.getString(R.string.error_unknown) } returns "unknown"

        viewModel.verifyPin(email, otp)
        advanceUntilIdle()

        assertEquals("unknown", (viewModel.uiState.value as VerifyPinUiState.Error).message)
    }

    // ───────────────────────────── state helpers ─────────────────────────────

    @Test
    fun `resetState resets to Idle when not idle`() = runTest {
        every { context.getString(R.string.error_invalid_otp) } returns "x"
        viewModel.verifyPin(email, "12") // length guard -> Error (non-idle)

        viewModel.resetState()

        assertEquals(VerifyPinUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `resetState no-op when already idle`() = runTest {
        viewModel.resetState()
        assertEquals(VerifyPinUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `clearAfterSuccess sets state to Idle`() = runTest {
        every { context.getString(R.string.error_invalid_otp) } returns "x"
        viewModel.verifyPin(email, "12") // -> Error

        viewModel.clearAfterSuccess()

        assertEquals(VerifyPinUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `setUserLoggedIn delegates to prefs`() = runTest {
        viewModel.setUserLoggedIn(true)
        verify(exactly = 1) { prefs.setUserLoggedIn(true) }

        viewModel.setUserLoggedIn(false)
        verify(exactly = 1) { prefs.setUserLoggedIn(false) }
    }
}
