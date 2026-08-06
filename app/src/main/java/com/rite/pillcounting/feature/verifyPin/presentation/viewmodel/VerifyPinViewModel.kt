package com.rite.pillcounting.feature.verifyPin.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.rite.pillcounting.BuildConfig
import com.rite.pillcounting.R
import com.rite.pillcounting.core.models.ErrorResponse
import com.rite.pillcounting.core.utils.common.NetworkUtils
import com.rite.pillcounting.core.utils.device.DeviceKeyProvider
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.otp.data.VerifyPinRepository
import com.rite.pillcounting.feature.verifyPin.domain.model.VerifyPinUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

/**
 * ViewModel responsible for verifying OTP codes and managing token persistence.
 *
 * ---
 * ### Responsibilities
 * - Validate and verify OTP input via [VerifyPinRepository].
 * - Persist access/refresh tokens on successful verification.
 * - Update [uiState] with friendly error or success states.
 * - Ensure user feedback is meaningful, masking technical details.
 *
 * ---
 * @param repository The [VerifyPinRepository] handling OTP verification API.
 * @param context The application context, used for localized messages.
 * @param prefs Secure storage for tokens and login state.
 * @param deviceKeyProvider Supplies the stable per-install device_key.
 */
@HiltViewModel
class VerifyPinViewModel @Inject constructor(
    private val repository: VerifyPinRepository,
    @ApplicationContext private val context: Context,
    private val prefs: PreferenceHelper,
    private val deviceKeyProvider: DeviceKeyProvider
) : ViewModel() {

    private val logger = AppLogger.create<VerifyPinViewModel>()

    private val _uiState = MutableStateFlow<VerifyPinUiState>(VerifyPinUiState.Idle)
    val uiState = _uiState.asStateFlow()

    /**
     * Verifies the entered OTP against the backend.
     *
     * @param email User’s email address (for logging/debug).
     * @param otp The 6-digit OTP entered by the user.
     */
    fun verifyPin(email: String, otp: String) {
        if (otp.length != 6) {
            _uiState.value = VerifyPinUiState.Error(
                context.getString(R.string.error_invalid_otp)
            )
            return
        }

        if (_uiState.value is VerifyPinUiState.Loading) return

        viewModelScope.launch {
            // Check network connectivity before calling API
            if (!NetworkUtils.isNetworkAvailable(context)) {
                _uiState.value = VerifyPinUiState.Error(
                    context.getString(R.string.error_no_internet)
                )
                logger.w("OTP verification aborted: no internet connection.")
                return@launch
            }

            logger.i("Attempting OTP verification for email: $email")
            _uiState.value = VerifyPinUiState.Loading

            val deviceKey = deviceKeyProvider.getDeviceKey()

            repository.verifyPin(email, otp, deviceKey, BuildConfig.VERSION_NAME)
                .onSuccess { response ->
                    logger.i("OTP verification success: ${response.status} / ${response.message}")

                    val data = response.data
                    val accessToken = data?.accessToken
                    val refreshToken = data?.refreshToken
//                    val isHL7Enabled = data?.user?.isHl7Enabled ?:false
                    val user = data?.user

                    if (!accessToken.isNullOrBlank() && !refreshToken.isNullOrBlank()) {
                        prefs.saveTokens(accessToken, refreshToken)
//                        prefs.setHl7Enabled(isHL7Enabled)
                        logger.i("Access and refresh tokens saved securely.")
                    } else {
                        logger.w("Missing access or refresh token in response.")
                    }


                    // Log user info (safely)
                    user?.let {
                        logger.i("User verified: email=${it.email}, verified=${it.isVerified}, role=${it.role}")
                    } ?: logger.w("User object is null in response.")

                    _uiState.value = VerifyPinUiState.Success
                }
                .onFailure { exception ->
                    logger.e("OTP verification failed for user: $email", exception)
                    val message = mapExceptionToUserMessage(exception)
                    _uiState.value = VerifyPinUiState.Error(message)
                }
        }
    }

    /**
     * Converts a thrown exception into a clear, user-friendly message.
     */
    private fun mapExceptionToUserMessage(exception: Throwable): String {
        return when (exception) {
            is IOException -> {
                // Network or server connectivity issue
                context.getString(R.string.error_server_unavailable)
            }
            is HttpException -> {
                val code = exception.code()
                val errorBody = exception.response()?.errorBody()?.string()
                val apiMessage = errorBody?.let {
                    try {
                        val errorResponse = Gson().fromJson(it, ErrorResponse::class.java)
                        errorResponse.message
                    } catch (e: Exception) {
                        logger.e("Error parsing error response", e)
                        null
                    }
                }

                when {
                    code == 401 -> context.getString(R.string.error_invalid_otp) // because session is not created yet after verify otp session will create that's why we are showing invalid otp error
                    code == 400 -> apiMessage ?: context.getString(R.string.error_invalid_otp)
                    code in 500..599 -> context.getString(R.string.error_server_down)
                    else -> apiMessage ?: context.getString(R.string.error_unknown)
                }
            }
            else -> {
                context.getString(R.string.error_unknown)
            }
        }
    }

    /** Resets UI state to Idle — used after user interaction. */
    fun resetState() {
        if (_uiState.value !is VerifyPinUiState.Idle) {
            _uiState.value = VerifyPinUiState.Idle
        }
    }

    /** Called after success to reset the screen for future usage. */
    fun clearAfterSuccess() {
        _uiState.value = VerifyPinUiState.Idle
    }

    /** Marks the user as logged in persistently. */
    fun setUserLoggedIn(isLoggedIn: Boolean) {
        prefs.setUserLoggedIn(isLoggedIn)
    }
}
