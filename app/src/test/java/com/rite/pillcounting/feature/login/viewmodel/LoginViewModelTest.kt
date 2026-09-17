package com.rite.pillcounting.feature.login.viewmodel

import android.content.Context
import android.util.Log
import app.cash.turbine.test
import com.rite.pillcounting.R
import com.rite.pillcounting.core.hl7.service.Hl7serviceHandler
import com.rite.pillcounting.core.models.ValidationResult
import com.rite.pillcounting.core.utils.common.NetworkUtils
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.core.utils.validator.CredentialsValidator
import com.rite.pillcounting.feature.login.data.LoginRepository
import com.rite.pillcounting.feature.login.domain.model.LoginResponse
import com.rite.pillcounting.feature.login.domain.model.LoginUiState
import com.rite.pillcounting.feature.login.domain.model.LogoutResponse
import com.rite.pillcounting.feature.login.domain.model.LogoutUiState
import com.rite.pillcounting.feature.login.presentation.viewmodel.LoginViewModel
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
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Unit tests for [com.rite.pillcounting.feature.login.presentation.viewmodel.LoginViewModel].
 *
 * Targets 100% line + method coverage including every branch of the private
 * getFriendlyErrorMessage(...) reached through login()/logout() onFailure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    private lateinit var repository: LoginRepository
    private lateinit var validator: CredentialsValidator
    private lateinit var context: Context
    private lateinit var preferenceHelper: PreferenceHelper
    private lateinit var serviceManager: Hl7serviceHandler
    private lateinit var viewModel: LoginViewModel

    private val email = "user@test.com"

    @Before
    fun setup() {
        // AppLogger wraps android.util.Log, which is not available on the JVM.
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
        validator = mockk()
        context = mockk()
        preferenceHelper = mockk(relaxed = true)
        serviceManager = mockk(relaxed = true)

        every { context.getString(any()) } returns "msg"

        viewModel = LoginViewModel(repository, validator, context, preferenceHelper, serviceManager)
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

    /** Parseable JSON that Gson maps to ErrorResponse.message = "parsed-msg". */
    private val validErrorJson =
        """{"status":400,"is_success":false,"message":"parsed-msg","token":null,"data":{}}"""

    /** Body that fails Gson parse -> parsedMessage == null (catch branch). */
    private val invalidJson = "<<not-json>>"

    // ───────────────────────────── login: validation branches ─────────────────────────────

    @Test
    fun `login with invalid email and errorMessageResId set uses resId string`() = runTest {
        every { validator.validateEmail(email) } returns
            ValidationResult(false, R.string.error_email_invalid)
        every { context.getString(R.string.error_email_invalid) } returns "bad-email"

        viewModel.login(email)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is LoginUiState.Error)
        assertEquals("bad-email", (state as LoginUiState.Error).message)
        coVerify(exactly = 0) { repository.login(any()) }
    }

    @Test
    fun `login with invalid email and null resId falls back to default invalid email string`() =
        runTest {
            every { validator.validateEmail(email) } returns ValidationResult(false, null)
            every { context.getString(R.string.error_invalid_email) } returns "default-invalid"

            viewModel.login(email)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state is LoginUiState.Error)
            assertEquals("default-invalid", (state as LoginUiState.Error).message)
        }

    // ───────────────────────────── login: already-loading guard ─────────────────────────────

    @Test
    fun `login returns early when already loading`() = runTest {
        every { validator.validateEmail(email) } returns ValidationResult(true)
        // Suspend the first call so the VM stays in Loading while the second call runs.
        val gate = CompletableDeferred<Result<LoginResponse>>()
        coEvery { repository.login(email) } coAnswers { gate.await() }

        viewModel.login(email) // schedules coroutine #1
        advanceUntilIdle()     // coroutine #1 sets Loading, suspends on gate
        assertEquals(LoginUiState.Loading, viewModel.uiState.value)

        viewModel.login(email) // state is Loading -> guard hit, returns early
        advanceUntilIdle()

        gate.complete(Result.success(LoginResponse()))
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.login(email) }
        verify(exactly = 1) { serviceManager.startService() }
    }

    // ───────────────────────────── login: no internet ─────────────────────────────

    @Test
    fun `login with no internet sets Error`() = runTest {
        every { validator.validateEmail(email) } returns ValidationResult(true)
        every { NetworkUtils.isNetworkAvailable(any()) } returns false
        every { context.getString(R.string.error_no_internet) } returns "no-internet"

        viewModel.login(email)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is LoginUiState.Error)
        assertEquals("no-internet", (state as LoginUiState.Error).message)
        coVerify(exactly = 0) { repository.login(any()) }
    }

    // ───────────────────────────── login: success ─────────────────────────────

    @Test
    fun `login success emits Loading then Success and starts service`() = runTest {
        every { validator.validateEmail(email) } returns ValidationResult(true)
        coEvery { repository.login(email) } returns Result.success(LoginResponse())

        viewModel.uiState.test {
            assertEquals(LoginUiState.Idle, awaitItem())
            viewModel.login(email)
            assertEquals(LoginUiState.Loading, awaitItem())
            assertEquals(LoginUiState.Success, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        advanceUntilIdle()

        verify(exactly = 1) { serviceManager.startService() }
    }

    // ───────────────── login failure: getFriendlyErrorMessage branches ─────────────────

    @Test
    fun `login failure HttpException 400 with parsed message`() = runTest {
        every { validator.validateEmail(email) } returns ValidationResult(true)
        coEvery { repository.login(email) } returns
            Result.failure(httpException(400, validErrorJson))

        viewModel.login(email)
        advanceUntilIdle()

        assertEquals("parsed-msg", (viewModel.uiState.value as LoginUiState.Error).message)
    }

    @Test
    fun `login failure HttpException 400 with unparseable body falls back`() = runTest {
        every { validator.validateEmail(email) } returns ValidationResult(true)
        coEvery { repository.login(email) } returns
            Result.failure(httpException(400, invalidJson))
        every { context.getString(R.string.error_invalid_email) } returns "fallback-400"

        viewModel.login(email)
        advanceUntilIdle()

        assertEquals("fallback-400", (viewModel.uiState.value as LoginUiState.Error).message)
    }

    @Test
    fun `login failure HttpException 401`() = runTest {
        every { validator.validateEmail(email) } returns ValidationResult(true)
        coEvery { repository.login(email) } returns
            Result.failure(httpException(401, invalidJson))
        every { context.getString(R.string.error_unauthorized) } returns "unauthorized"

        viewModel.login(email)
        advanceUntilIdle()

        assertEquals("unauthorized", (viewModel.uiState.value as LoginUiState.Error).message)
    }

    @Test
    fun `login failure HttpException 500`() = runTest {
        every { validator.validateEmail(email) } returns ValidationResult(true)
        coEvery { repository.login(email) } returns
            Result.failure(httpException(500, invalidJson))
        every { context.getString(R.string.error_server_unavailable) } returns "server-unavailable"

        viewModel.login(email)
        advanceUntilIdle()

        assertEquals("server-unavailable", (viewModel.uiState.value as LoginUiState.Error).message)
    }

    @Test
    fun `login failure HttpException else-code with parsed message`() = runTest {
        every { validator.validateEmail(email) } returns ValidationResult(true)
        coEvery { repository.login(email) } returns
            Result.failure(httpException(418, validErrorJson))

        viewModel.login(email)
        advanceUntilIdle()

        assertEquals("parsed-msg", (viewModel.uiState.value as LoginUiState.Error).message)
    }

    @Test
    fun `login failure HttpException else-code with null parsed message falls back to generic`() =
        runTest {
            every { validator.validateEmail(email) } returns ValidationResult(true)
            coEvery { repository.login(email) } returns
                Result.failure(httpException(418, invalidJson))
            every { context.getString(R.string.error_generic) } returns "generic"

            viewModel.login(email)
            advanceUntilIdle()

            assertEquals("generic", (viewModel.uiState.value as LoginUiState.Error).message)
        }

    @Test
    fun `login failure UnknownHostException`() = runTest {
        every { validator.validateEmail(email) } returns ValidationResult(true)
        coEvery { repository.login(email) } returns Result.failure(UnknownHostException("no host"))
        every { context.getString(R.string.error_no_internet) } returns "no-internet"

        viewModel.login(email)
        advanceUntilIdle()

        assertEquals("no-internet", (viewModel.uiState.value as LoginUiState.Error).message)
    }

    @Test
    fun `login failure SocketTimeoutException`() = runTest {
        every { validator.validateEmail(email) } returns ValidationResult(true)
        coEvery { repository.login(email) } returns Result.failure(SocketTimeoutException("timeout"))
        every { context.getString(R.string.error_timeout) } returns "timeout"

        viewModel.login(email)
        advanceUntilIdle()

        assertEquals("timeout", (viewModel.uiState.value as LoginUiState.Error).message)
    }

    @Test
    fun `login failure generic exception with non-blank message uses message`() = runTest {
        every { validator.validateEmail(email) } returns ValidationResult(true)
        coEvery { repository.login(email) } returns Result.failure(RuntimeException("boom"))

        viewModel.login(email)
        advanceUntilIdle()

        assertEquals("boom", (viewModel.uiState.value as LoginUiState.Error).message)
    }

    @Test
    fun `login failure generic exception with blank message falls back to generic`() = runTest {
        every { validator.validateEmail(email) } returns ValidationResult(true)
        coEvery { repository.login(email) } returns Result.failure(RuntimeException("   "))
        every { context.getString(R.string.error_generic) } returns "generic"

        viewModel.login(email)
        advanceUntilIdle()

        assertEquals("generic", (viewModel.uiState.value as LoginUiState.Error).message)
    }

    // ───────────────────────────── logout ─────────────────────────────

    @Test
    fun `logout returns early when already loading`() = runTest {
        val gate = CompletableDeferred<Result<LogoutResponse>>()
        coEvery { repository.logout("refresh") } coAnswers { gate.await() }

        viewModel.logout("refresh") // schedules coroutine #1
        advanceUntilIdle()          // coroutine #1 sets Loading, suspends on gate
        assertEquals(LogoutUiState.Loading, viewModel.logoutUiState.value)

        viewModel.logout("refresh") // guard hit, returns early
        advanceUntilIdle()

        gate.complete(Result.success(LogoutResponse()))
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.logout("refresh") }
        verify(exactly = 1) { preferenceHelper.clearHl7Config() }
    }

    @Test
    fun `logout with no internet sets Error`() = runTest {
        every { NetworkUtils.isNetworkAvailable(any()) } returns false
        every { context.getString(R.string.error_no_internet) } returns "no-internet"

        viewModel.logout("refresh")
        advanceUntilIdle()

        val state = viewModel.logoutUiState.value
        assertTrue(state is LogoutUiState.Error)
        assertEquals("no-internet", (state as LogoutUiState.Error).message)
        coVerify(exactly = 0) { repository.logout(any()) }
    }

    @Test
    fun `logout success emits Loading then Success and clears hl7 config`() = runTest {
        coEvery { repository.logout("refresh") } returns Result.success(LogoutResponse())

        viewModel.logoutUiState.test {
            assertEquals(LogoutUiState.Idle, awaitItem())
            viewModel.logout("refresh")
            assertEquals(LogoutUiState.Loading, awaitItem())
            assertEquals(LogoutUiState.Success, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        advanceUntilIdle()

        verify(exactly = 1) { preferenceHelper.clearHl7Config() }
    }

    @Test
    fun `logout failure sets Error using friendly message`() = runTest {
        coEvery { repository.logout("refresh") } returns
            Result.failure(httpException(401, invalidJson))
        every { context.getString(R.string.error_unauthorized) } returns "unauthorized"

        viewModel.logout("refresh")
        advanceUntilIdle()

        val state = viewModel.logoutUiState.value
        assertTrue(state is LogoutUiState.Error)
        assertEquals("unauthorized", (state as LogoutUiState.Error).message)
    }

    // ───────────────────────────── state reset helpers ─────────────────────────────

    @Test
    fun `resetLoginState resets to Idle when not idle`() = runTest {
        every { validator.validateEmail(email) } returns ValidationResult(false, null)
        every { context.getString(R.string.error_invalid_email) } returns "x"
        viewModel.login(email) // -> Error (non-idle)

        viewModel.resetLoginState()

        assertEquals(LoginUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `resetLoginState no-op when already idle`() = runTest {
        viewModel.resetLoginState()
        assertEquals(LoginUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `clearSession clears tokens, sets logged out and resets local id`() = runTest {
        viewModel.clearSession()

        verify(exactly = 1) { preferenceHelper.clearTokens() }
        verify(exactly = 1) { preferenceHelper.setUserLoggedIn(false) }
        verify(exactly = 1) { preferenceHelper.saveLocalId(0) }
    }

    @Test
    fun `clearAllStates resets both states to idle`() = runTest {
        every { validator.validateEmail(email) } returns ValidationResult(false, null)
        every { context.getString(R.string.error_invalid_email) } returns "x"
        viewModel.login(email) // drive uiState to Error

        viewModel.clearAllStates()

        assertEquals(LoginUiState.Idle, viewModel.uiState.value)
        assertEquals(LogoutUiState.Idle, viewModel.logoutUiState.value)
    }
}
