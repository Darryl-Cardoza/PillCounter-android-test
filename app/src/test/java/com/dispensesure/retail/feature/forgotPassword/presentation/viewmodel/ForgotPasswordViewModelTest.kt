package com.dispensesure.retail.feature.forgotPassword.presentation.viewmodel//package com.example.pillcountingnewmodels.feature.forgotPassword.presentation.viewmodel
//
//import android.content.Context
//import app.cash.turbine.test
//import com.example.pillcountingnewmodels.R
//import com.example.pillcountingnewmodels.core.models.ValidationResult
//import com.example.pillcountingnewmodels.core.utils.AppLogger
//import com.example.pillcountingnewmodels.feature.forgotPassword.data.ForgotPasswordRepository
//import com.example.pillcountingnewmodels.feature.forgotPassword.domain.model.ForgotPasswordResponse
//import com.example.pillcountingnewmodels.feature.forgotPassword.domain.model.ForgotPasswordUiState
//import com.example.pillcountingnewmodels.feature.forgotPassword.viewmodel.ForgotPasswordViewModel
//import com.example.pillcountingnewmodels.feature.login.domain.CredentialsValidator
//import io.mockk.*
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.ExperimentalCoroutinesApi
//import kotlinx.coroutines.delay
//import kotlinx.coroutines.launch
//import kotlinx.coroutines.test.*
//import org.junit.After
//import org.junit.Assert.assertEquals
//import org.junit.Before
//import org.junit.Test
//
//@OptIn(ExperimentalCoroutinesApi::class)
//class ForgotPasswordViewModelTest {
//
//    private val testDispatcher = StandardTestDispatcher()
//
//    private lateinit var repository: ForgotPasswordRepository
//    private lateinit var validator: CredentialsValidator
//    private lateinit var context: Context
//    private lateinit var viewModel: ForgotPasswordViewModel
//
//    val mockForgotPasswordResponse = ForgotPasswordResponse(
//        message = "OTP sent successfully"
//    )
//
//    @Before
//    fun setup() {
//        Dispatchers.setMain(testDispatcher)
//        repository = mockk()
//        validator = mockk()
//        context = mockk()
//        every { context.getString(any()) } answers { "Error" } // Default error string
//        mockkObject(AppLogger) // suppress logger output
//        every { AppLogger.create<ForgotPasswordViewModel>() } returns mockk(relaxed = true)
//
//        viewModel = ForgotPasswordViewModel(repository, validator, context)
//    }
//
//    @After
//    fun tearDown() {
//        Dispatchers.resetMain()
//        unmockkAll()
//    }
//
//    @Test
//    fun `sendOtp with invalid email updates state to Error`() = runTest {
//        val email = "invalid_email"
//        val errorResId = R.string.error_invalid_email
//
//        every { validator.validateEmail(email) } returns ValidationResult(
//            isSuccess = false,
//            errorMessageResId = errorResId
//        )
//        every { context.getString(errorResId) } returns "Invalid email"
//
//        viewModel.uiState.test {
//            viewModel.sendOtp(email)
//            assertEquals(ForgotPasswordUiState.Error("Invalid email"), awaitItem())
//        }
//    }
//
//    @Test
//    fun `sendOtp with valid email and repository success updates state to Success`() = runTest {
//        val email = "test@example.com"
//
//        every { validator.validateEmail(email) } returns ValidationResult(
//            isSuccess = true,
//            errorMessageResId = null
//        )
//        coEvery { repository.sendOtp(email) } returns Result.success(mockForgotPasswordResponse)
//
//        viewModel.uiState.test {
//            viewModel.sendOtp(email)
//            advanceUntilIdle()
//            assertEquals(ForgotPasswordUiState.Loading, awaitItem())
//            assertEquals(ForgotPasswordUiState.Success(email), awaitItem())
//        }
//    }
//
//    @Test
//    fun `sendOtp with repository failure updates state to Error`() = runTest {
//        val email = "test@example.com"
//        val exception = Exception("Network error")
//
//        every { validator.validateEmail(email) } returns ValidationResult(
//            isSuccess = true,
//            errorMessageResId = null
//        )
//        coEvery { repository.sendOtp(email) } returns Result.failure(exception)
//        every { context.getString(R.string.error_unknown) } returns "Unknown error"
//
//        viewModel.uiState.test {
//            viewModel.sendOtp(email)
//            advanceUntilIdle()
//            assertEquals(ForgotPasswordUiState.Loading, awaitItem())
//            assertEquals(ForgotPasswordUiState.Error("Network error"), awaitItem())
//        }
//    }
//
//    @Test
//    fun `sendOtp does nothing when already loading`() = runTest {
//        val email = "test@example.com"
//
//        // simulate Loading state
//        viewModel = spyk(ForgotPasswordViewModel(repository, validator, context))
//        viewModel.uiState.update { ForgotPasswordUiState.Loading }
//
//        viewModel.sendOtp(email)
//        // Verify repository is never called
//        coVerify(exactly = 0) { repository.sendOtp(any()) }
//    }
//
//    @Test
//    fun `resetState sets state to Idle`() = runTest {
//        viewModel.uiState. (ForgotPasswordUiState.Error("Some error"))
//
//        viewModel.uiState.test {
//            viewModel.resetState()
//            assertEquals(ForgotPasswordUiState.Idle, awaitItem())
//        }
//    }
//
//
//    @Test
//    fun `resetState does nothing if already Idle`() = runTest {
//        viewModel.uiState.test {
//            viewModel.resetState()
//            expectNoEvents() // No state change
//        }
//    }
//
//    @Test
//    fun `sendOtp prevents duplicate calls when called rapidly`() = runTest {
//        val email = "test@example.com"
//
//        every { validator.validateEmail(email) } returns ValidationResult(
//            isSuccess = true,
//            errorMessageResId = null
//        )
//        coEvery { repository.sendOtp(email) } coAnswers {
//            delay(100) // simulate network delay
//            Result.success(mockForgotPasswordResponse)
//        }
//
//        viewModel.uiState.test {
//            // Launch multiple sendOtp calls rapidly
//            launch { viewModel.sendOtp(email) }
//            launch { viewModel.sendOtp(email) }
//            launch { viewModel.sendOtp(email) }
//
//            advanceTimeBy(50)  // first call in progress
//            coVerify(exactly = 1) { repository.sendOtp(email) } // only 1 call so far
//
//            advanceUntilIdle()
//            // Verify state transitions
//            assertEquals(ForgotPasswordUiState.Loading, awaitItem())
//            assertEquals(ForgotPasswordUiState.Success(email), awaitItem())
//
//            coVerify(exactly = 1) { repository.sendOtp(email) } // repository called only once
//        }
//    }
//
//}
