package com.rite.pillcounting.feature.login.viewmodel

import android.content.Context
import app.cash.turbine.test
import com.rite.pillcounting.core.hl7.service.Hl7serviceHandler
import com.rite.pillcounting.core.utils.common.NetworkUtils
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.core.utils.validator.CredentialsValidator
import com.rite.pillcounting.core.models.ValidationResult
import com.rite.pillcounting.feature.login.data.LoginRepository
import com.rite.pillcounting.feature.login.domain.model.LoginResponse
import com.rite.pillcounting.feature.login.domain.model.LoginUiState
import com.rite.pillcounting.feature.login.domain.model.LogoutResponse
import com.rite.pillcounting.feature.login.domain.model.LogoutUiState
import com.rite.pillcounting.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.Runs
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.SocketTimeoutException
import java.net.UnknownHostException

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository: LoginRepository = mockk()
    private val validator: CredentialsValidator = mockk()
    private val context: Context = mockk(relaxed = true)
    private val preferenceHelper: PreferenceHelper = mockk(relaxed = true)
    private val serviceManager: Hl7serviceHandler = mockk(relaxed = true)

    private lateinit var viewModel: LoginViewModel

    @Before
    fun setup() {
        mockkObject(NetworkUtils)
        viewModel = LoginViewModel(repository, validator, context, preferenceHelper, serviceManager)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // -------------------------------------------------------------------------
    // login()
    // -------------------------------------------------------------------------

    // LOG_VM_001
    @Test
    fun `login emits Loading then Success when email valid and repo succeeds`() = runTest {
        val email = "user@pharmacy.com"
        every { validator.validateEmail(email) } returns ValidationResult(true)
        every { NetworkUtils.isNetworkAvailable(context) } returns true
        coEvery { repository.login(email) } returns Result.success(LoginResponse())

        viewModel.uiState.test {
            assertEquals(LoginUiState.Idle, awaitItem())
            viewModel.login(email)
            assertEquals(LoginUiState.Loading, awaitItem())
            assertEquals(LoginUiState.Success, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // LOG_VM_002
    @Test
    fun `login emits Error immediately when email validation fails`() = runTest {
        val email = "bad-email"
        every { validator.validateEmail(email) } returns ValidationResult(false, 0)
        every { context.getString(0) } returns "Invalid email"

        viewModel.uiState.test {
            awaitItem() // Idle
            viewModel.login(email)
            val state = awaitItem()
            assertTrue(state is LoginUiState.Error)
            coVerify(exactly = 0) { repository.login(any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    // LOG_VM_003
    @Test
    fun `login emits Error when network is unavailable`() = runTest {
        val email = "user@pharmacy.com"
        every { validator.validateEmail(email) } returns ValidationResult(true)
        every { NetworkUtils.isNetworkAvailable(context) } returns false
        every { context.getString(any()) } returns "No internet"

        viewModel.uiState.test {
            awaitItem() // Idle
            viewModel.login(email)
            val state = awaitItem()
            assertTrue(state is LoginUiState.Error)
            coVerify(exactly = 0) { repository.login(any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    // LOG_VM_004
    @Test
    fun `login emits Error when repository throws HttpException 401`() = runTest {
        val email = "user@pharmacy.com"
        val exception = HttpException(Response.error<Any>(401, "".toResponseBody(null)))
        every { validator.validateEmail(email) } returns ValidationResult(true)
        every { NetworkUtils.isNetworkAvailable(context) } returns true
        every { context.getString(any()) } returns "Unauthorized"
        coEvery { repository.login(email) } returns Result.failure(exception)

        viewModel.uiState.test {
            awaitItem() // Idle
            viewModel.login(email)
            advanceUntilIdle()
            awaitItem() // Loading
            val error = awaitItem()
            assertTrue(error is LoginUiState.Error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // LOG_VM_005
    @Test
    fun `login emits Error when repository throws HttpException 500`() = runTest {
        val email = "user@pharmacy.com"
        val exception = HttpException(Response.error<Any>(500, "".toResponseBody(null)))
        every { validator.validateEmail(email) } returns ValidationResult(true)
        every { NetworkUtils.isNetworkAvailable(context) } returns true
        every { context.getString(any()) } returns "Server error"
        coEvery { repository.login(email) } returns Result.failure(exception)

        viewModel.uiState.test {
            awaitItem() // Idle
            viewModel.login(email)
            advanceUntilIdle()
            awaitItem() // Loading
            val error = awaitItem()
            assertTrue(error is LoginUiState.Error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // LOG_VM_006
    @Test
    fun `login emits Error when repository throws UnknownHostException`() = runTest {
        val email = "user@pharmacy.com"
        every { validator.validateEmail(email) } returns ValidationResult(true)
        every { NetworkUtils.isNetworkAvailable(context) } returns true
        every { context.getString(any()) } returns "No internet"
        coEvery { repository.login(email) } returns Result.failure(UnknownHostException("no host"))

        viewModel.uiState.test {
            awaitItem() // Idle
            viewModel.login(email)
            advanceUntilIdle()
            awaitItem() // Loading
            val error = awaitItem()
            assertTrue(error is LoginUiState.Error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // LOG_VM_007
    @Test
    fun `login emits Error when repository throws SocketTimeoutException`() = runTest {
        val email = "user@pharmacy.com"
        every { validator.validateEmail(email) } returns ValidationResult(true)
        every { NetworkUtils.isNetworkAvailable(context) } returns true
        every { context.getString(any()) } returns "Timeout"
        coEvery { repository.login(email) } returns Result.failure(SocketTimeoutException("timeout"))

        viewModel.uiState.test {
            awaitItem() // Idle
            viewModel.login(email)
            advanceUntilIdle()
            awaitItem() // Loading
            val error = awaitItem()
            assertTrue(error is LoginUiState.Error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // LOG_VM_008 — second call while Loading is silently dropped
    @Test
    fun `login does not start second call while Loading state is active`() = runTest {
        val email = "user@pharmacy.com"
        every { validator.validateEmail(email) } returns ValidationResult(true)
        every { NetworkUtils.isNetworkAvailable(context) } returns true
        coEvery { repository.login(email) } returns Result.success(LoginResponse())

        viewModel.login(email)           // first call → sets Loading
        advanceUntilIdle()               // let it reach Loading internally
        viewModel.login(email)           // second call while Loading → dropped

        // Repository should be called exactly once
        coVerify(exactly = 1) { repository.login(email) }
    }

    // LOG_VM_009 — on Success, Hl7serviceHandler.startService() is called
    @Test
    fun `login calls serviceManager startService on success`() = runTest {
        val email = "user@pharmacy.com"
        every { validator.validateEmail(email) } returns ValidationResult(true)
        every { NetworkUtils.isNetworkAvailable(context) } returns true
        coEvery { repository.login(email) } returns Result.success(LoginResponse())

        viewModel.uiState.test {
            awaitItem() // Idle
            viewModel.login(email)
            advanceUntilIdle()
            awaitItem() // Loading
            awaitItem() // Success
            verify(exactly = 1) { serviceManager.startService() }
            cancelAndIgnoreRemainingEvents()
        }
    }

    // -------------------------------------------------------------------------
    // logout()
    // -------------------------------------------------------------------------

    // LOG_VM_010
    @Test
    fun `logout emits Loading then Success and calls clearHl7Config`() = runTest {
        val token = "refresh_token_abc"
        every { NetworkUtils.isNetworkAvailable(context) } returns true
        coEvery { repository.logout(token) } returns Result.success(LogoutResponse())

        viewModel.logoutUiState.test {
            assertEquals(LogoutUiState.Idle, awaitItem())
            viewModel.logout(token)
            assertEquals(LogoutUiState.Loading, awaitItem())
            assertEquals(LogoutUiState.Success, awaitItem())
            verify { preferenceHelper.clearHl7Config() }
            cancelAndIgnoreRemainingEvents()
        }
    }

    // LOG_VM_011
    @Test
    fun `logout emits Error when repository fails`() = runTest {
        val token = "refresh_token_abc"
        every { NetworkUtils.isNetworkAvailable(context) } returns true
        every { context.getString(any()) } returns "Logout failed"
        coEvery { repository.logout(token) } returns Result.failure(Exception("error"))

        viewModel.logoutUiState.test {
            awaitItem() // Idle
            viewModel.logout(token)
            advanceUntilIdle()
            awaitItem() // Loading
            val error = awaitItem()
            assertTrue(error is LogoutUiState.Error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // LOG_VM_012
    @Test
    fun `logout emits Error when network is unavailable`() = runTest {
        every { NetworkUtils.isNetworkAvailable(context) } returns false
        every { context.getString(any()) } returns "No internet"

        viewModel.logoutUiState.test {
            awaitItem() // Idle
            viewModel.logout("token")
            val error = awaitItem()
            assertTrue(error is LogoutUiState.Error)
            coVerify(exactly = 0) { repository.logout(any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    // -------------------------------------------------------------------------
    // resetLoginState / clearSession / clearAllStates
    // -------------------------------------------------------------------------

    // LOG_VM_013
    @Test
    fun `resetLoginState sets uiState to Idle from Error`() = runTest {
        // Drive state to Error first (validation fails synchronously)
        every { validator.validateEmail("x") } returns ValidationResult(false, 0)
        every { context.getString(0) } returns "err"
        viewModel.login("x")

        viewModel.resetLoginState()
        assertEquals(LoginUiState.Idle, viewModel.uiState.value)
    }

    // LOG_VM_014
    @Test
    fun `resetLoginState is a no-op when already Idle`() = runTest {
        assertEquals(LoginUiState.Idle, viewModel.uiState.value)
        viewModel.resetLoginState()
        assertEquals(LoginUiState.Idle, viewModel.uiState.value)
    }

    // LOG_VM_015
    @Test
    fun `clearSession calls clearTokens setUserLoggedIn and saveLocalId on preferenceHelper`() {
        viewModel.clearSession()
        verify { preferenceHelper.clearTokens() }
        verify { preferenceHelper.setUserLoggedIn(false) }
        verify { preferenceHelper.saveLocalId(0) }
    }

    // LOG_VM_016
    @Test
    fun `clearAllStates resets both uiState and logoutUiState to Idle`() = runTest {
        viewModel.clearAllStates()
        assertEquals(LoginUiState.Idle, viewModel.uiState.value)
        assertEquals(LogoutUiState.Idle, viewModel.logoutUiState.value)
    }
}
