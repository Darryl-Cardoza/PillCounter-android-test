package com.rite.pillcounting.feature.verifyPin.viewmodel

import android.content.Context
import app.cash.turbine.test
import com.rite.pillcounting.core.utils.common.NetworkUtils
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.otp.data.VerifyPinRepository
import com.rite.pillcounting.feature.verifyPin.domain.model.VerifyPinData
import com.rite.pillcounting.feature.verifyPin.domain.model.VerifyPinResponse
import com.rite.pillcounting.feature.verifyPin.domain.model.VerifyPinUiState
import com.rite.pillcounting.feature.verifyPin.presentation.viewmodel.VerifyPinViewModel
import com.rite.pillcounting.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
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
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class VerifyPinViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository: VerifyPinRepository = mockk()
    private val context: Context = mockk(relaxed = true)
    private val prefs: PreferenceHelper = mockk(relaxed = true)

    private lateinit var viewModel: VerifyPinViewModel

    @Before
    fun setup() {
        mockkObject(NetworkUtils)
        viewModel = VerifyPinViewModel(repository, context, prefs)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // -------------------------------------------------------------------------
    // verifyPin()
    // -------------------------------------------------------------------------

    // VP_VM_001
    @Test
    fun `verifyPin emits Loading then Success and saves tokens when OTP is valid`() = runTest {
        val email = "user@pharmacy.com"
        val otp = "1234"
        val data = VerifyPinData(accessToken = "access_tok", refreshToken = "refresh_tok")
        val response = VerifyPinResponse(status = 200, isSuccess = true, data = data)
        every { NetworkUtils.isNetworkAvailable(context) } returns true
        coEvery { repository.verifyPin(email, otp) } returns Result.success(response)

        viewModel.uiState.test {
            assertEquals(VerifyPinUiState.Idle, awaitItem())
            viewModel.verifyPin(email, otp)
            assertEquals(VerifyPinUiState.Loading, awaitItem())
            assertEquals(VerifyPinUiState.Success, awaitItem())
            verify { prefs.saveTokens("access_tok", "refresh_tok") }
            cancelAndIgnoreRemainingEvents()
        }
    }

    // VP_VM_002 — OTP shorter than 4
    @Test
    fun `verifyPin emits Error immediately when OTP length is less than 4`() = runTest {
        every { context.getString(any()) } returns "Invalid OTP"

        viewModel.uiState.test {
            awaitItem() // Idle
            viewModel.verifyPin("user@pharmacy.com", "12")
            val state = awaitItem()
            assertTrue(state is VerifyPinUiState.Error)
            coVerify(exactly = 0) { repository.verifyPin(any(), any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    // VP_VM_003 — OTP longer than 4
    @Test
    fun `verifyPin emits Error immediately when OTP length is more than 4`() = runTest {
        every { context.getString(any()) } returns "Invalid OTP"

        viewModel.uiState.test {
            awaitItem() // Idle
            viewModel.verifyPin("user@pharmacy.com", "12345")
            val state = awaitItem()
            assertTrue(state is VerifyPinUiState.Error)
            coVerify(exactly = 0) { repository.verifyPin(any(), any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    // VP_VM_004 — empty OTP
    @Test
    fun `verifyPin emits Error immediately when OTP is empty`() = runTest {
        every { context.getString(any()) } returns "Invalid OTP"

        viewModel.uiState.test {
            awaitItem() // Idle
            viewModel.verifyPin("user@pharmacy.com", "")
            val state = awaitItem()
            assertTrue(state is VerifyPinUiState.Error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // VP_VM_005 — network unavailable (checked inside coroutine after Length guard)
    @Test
    fun `verifyPin emits Error when network is unavailable`() = runTest {
        every { NetworkUtils.isNetworkAvailable(context) } returns false
        every { context.getString(any()) } returns "No internet"

        viewModel.uiState.test {
            awaitItem() // Idle
            viewModel.verifyPin("user@pharmacy.com", "1234")
            advanceUntilIdle()
            // Loading is emitted AFTER network check — here network check fires before Loading
            val state = awaitItem()
            assertTrue(state is VerifyPinUiState.Error)
            coVerify(exactly = 0) { repository.verifyPin(any(), any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    // VP_VM_006 — HTTP 400
    @Test
    fun `verifyPin emits Error when repository returns HttpException 400`() = runTest {
        val email = "user@pharmacy.com"
        val otp = "1234"
        val exception = HttpException(Response.error<Any>(400, "".toResponseBody(null)))
        every { NetworkUtils.isNetworkAvailable(context) } returns true
        every { context.getString(any()) } returns "Invalid OTP"
        coEvery { repository.verifyPin(email, otp) } returns Result.failure(exception)

        viewModel.uiState.test {
            awaitItem() // Idle
            viewModel.verifyPin(email, otp)
            advanceUntilIdle()
            awaitItem() // Loading
            val error = awaitItem()
            assertTrue(error is VerifyPinUiState.Error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // VP_VM_007 — HTTP 401
    @Test
    fun `verifyPin emits Error when repository returns HttpException 401`() = runTest {
        val email = "user@pharmacy.com"
        val otp = "1234"
        val exception = HttpException(Response.error<Any>(401, "".toResponseBody(null)))
        every { NetworkUtils.isNetworkAvailable(context) } returns true
        every { context.getString(any()) } returns "Invalid OTP"
        coEvery { repository.verifyPin(email, otp) } returns Result.failure(exception)

        viewModel.uiState.test {
            awaitItem() // Idle
            viewModel.verifyPin(email, otp)
            advanceUntilIdle()
            awaitItem() // Loading
            val error = awaitItem()
            assertTrue(error is VerifyPinUiState.Error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // VP_VM_008 — IOException
    @Test
    fun `verifyPin emits Error when repository throws IOException`() = runTest {
        val email = "user@pharmacy.com"
        val otp = "1234"
        every { NetworkUtils.isNetworkAvailable(context) } returns true
        every { context.getString(any()) } returns "Server unavailable"
        coEvery { repository.verifyPin(email, otp) } returns Result.failure(IOException("connection reset"))

        viewModel.uiState.test {
            awaitItem() // Idle
            viewModel.verifyPin(email, otp)
            advanceUntilIdle()
            awaitItem() // Loading
            val error = awaitItem()
            assertTrue(error is VerifyPinUiState.Error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // VP_VM_009 — server 5xx
    @Test
    fun `verifyPin emits Error when repository returns HttpException 503`() = runTest {
        val email = "user@pharmacy.com"
        val otp = "1234"
        val exception = HttpException(Response.error<Any>(503, "".toResponseBody(null)))
        every { NetworkUtils.isNetworkAvailable(context) } returns true
        every { context.getString(any()) } returns "Server down"
        coEvery { repository.verifyPin(email, otp) } returns Result.failure(exception)

        viewModel.uiState.test {
            awaitItem() // Idle
            viewModel.verifyPin(email, otp)
            advanceUntilIdle()
            awaitItem() // Loading
            val error = awaitItem()
            assertTrue(error is VerifyPinUiState.Error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // VP_VM_010 — tokens NOT saved when response has null tokens
    @Test
    fun `verifyPin does not save tokens when accessToken is null in response`() = runTest {
        val email = "user@pharmacy.com"
        val otp = "1234"
        val response = VerifyPinResponse(status = 200, isSuccess = true, data = VerifyPinData(accessToken = null, refreshToken = null))
        every { NetworkUtils.isNetworkAvailable(context) } returns true
        coEvery { repository.verifyPin(email, otp) } returns Result.success(response)

        viewModel.uiState.test {
            awaitItem() // Idle
            viewModel.verifyPin(email, otp)
            advanceUntilIdle()
            awaitItem() // Loading
            awaitItem() // Success
            coVerify(exactly = 0) { prefs.saveTokens(any(), any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    // -------------------------------------------------------------------------
    // resetState / clearAfterSuccess / setUserLoggedIn
    // -------------------------------------------------------------------------

    // VP_VM_011
    @Test
    fun `resetState returns uiState to Idle from Error`() = runTest {
        every { context.getString(any()) } returns "Invalid OTP"
        viewModel.verifyPin("user@pharmacy.com", "12") // triggers Error (length check)
        viewModel.resetState()
        assertEquals(VerifyPinUiState.Idle, viewModel.uiState.value)
    }

    // VP_VM_012
    @Test
    fun `resetState is a no-op when already Idle`() {
        assertEquals(VerifyPinUiState.Idle, viewModel.uiState.value)
        viewModel.resetState()
        assertEquals(VerifyPinUiState.Idle, viewModel.uiState.value)
    }

    // VP_VM_013
    @Test
    fun `clearAfterSuccess sets uiState to Idle`() = runTest {
        viewModel.clearAfterSuccess()
        assertEquals(VerifyPinUiState.Idle, viewModel.uiState.value)
    }

    // VP_VM_014
    @Test
    fun `setUserLoggedIn delegates to PreferenceHelper`() {
        viewModel.setUserLoggedIn(true)
        verify { prefs.setUserLoggedIn(true) }

        viewModel.setUserLoggedIn(false)
        verify { prefs.setUserLoggedIn(false) }
    }
}
