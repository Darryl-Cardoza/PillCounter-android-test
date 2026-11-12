package com.dispensesure.retail.feature.verifyPin.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dispensesure.retail.feature.verifyPin.data.VerifyPinRepository
import com.google.gson.Gson
import com.dispensesure.retail.BuildConfig
import com.dispensesure.retail.R
import com.dispensesure.retail.core.audit.AuditLogger
import com.dispensesure.retail.core.audit.model.AuthErrorCode
import com.dispensesure.retail.core.audit.model.LoginSucceeded
import com.dispensesure.retail.core.audit.model.OtpVerifyFailed
import com.dispensesure.retail.core.audit.model.authErrorCodeOf
import com.dispensesure.retail.core.audit.model.httpStatusOf
import com.dispensesure.retail.core.models.ErrorResponse
import com.dispensesure.retail.core.region.Region
import com.dispensesure.retail.core.utils.common.NetworkUtils
import com.dispensesure.retail.core.health.logic.SessionHealthController
import com.dispensesure.retail.core.utils.device.DeviceKeyProvider
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.logger.LogEvent
import com.dispensesure.retail.core.utils.logger.LoggerConfig
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import com.dispensesure.retail.feature.verifyPin.domain.model.VerifyPinUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
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
 * @param deviceKeyProvider Supplies the stable per-device device_key (SSAID).
 */
@HiltViewModel
class VerifyPinViewModel @Inject constructor(
    private val repository: VerifyPinRepository,
    @ApplicationContext private val context: Context,
    private val prefs: PreferenceHelper,
    private val deviceKeyProvider: DeviceKeyProvider,
    private val sessionHealthController: SessionHealthController,
    private val auditLogger: AuditLogger
) : ViewModel() {

    private val logger = AppLogger.create<VerifyPinViewModel>()

    private val _uiState = MutableStateFlow<VerifyPinUiState>(VerifyPinUiState.Idle)
    val uiState = _uiState.asStateFlow()

    /**
     * Verifies the entered OTP against the backend.
     *
     * @param email User’s email address (for logging/debug).
     * @param otp The 6-digit OTP entered by the user.
     * @param rememberMe The Login screen's "Remember me" choice, recorded on the login audit event.
     */
    fun verifyPin(email: String, otp: String, rememberMe: Boolean = false) {
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

            logger.i("Attempting OTP verification")
            _uiState.value = VerifyPinUiState.Loading

            val deviceKey = deviceKeyProvider.getDeviceKey()
            if (deviceKey == null) {
                logger.e("OTP verification aborted: device key unavailable", event = LogEvent.OTP_VERIFICATION_FAILED)
                auditLogger.log(OtpVerifyFailed(email, httpStatus = null, errorCode = AuthErrorCode.DEVICE_KEY_UNAVAILABLE))
                _uiState.value = VerifyPinUiState.Error(
                    context.getString(R.string.error_server_unavailable)
                )
                return@launch
            }

            repository.verifyPin(email, otp, deviceKey, BuildConfig.VERSION_NAME)
                .onSuccess { response ->
                    logger.i("OTP verification success: ${response.status} / ${response.message}")

                    val data = response.data
                    val accessToken = data?.accessToken
                    val refreshToken = data?.refreshToken
//                    val isHL7Enabled = data?.user?.isHl7Enabled ?:false
                    val user = data?.user

                    // No US/CA country means no region rules to run under; stop before saving anything.
                    val loggedInRegion = Region.fromCountryCode(user?.country)
                    if (loggedInRegion == null) {
                        logger.w("OTP verification blocked: unsupported country '${user?.country}'")
                        auditLogger.log(OtpVerifyFailed(email, httpStatus = null, errorCode = AuthErrorCode.UNSUPPORTED_REGION))
                        _uiState.value = VerifyPinUiState.Error(context.getString(R.string.error_unsupported_region))
                        return@onSuccess
                    }

                    // The API call already succeeded at this point — everything below is local
                    // persistence (SecurePreferences/AndroidKeyStore). A failure here (e.g. a
                    // KeyStore error) must not crash past a successful login uncaught; fall back
                    // to an error state instead so the user can retry rather than see a crash.
                    try {
                        if (!accessToken.isNullOrBlank() && !refreshToken.isNullOrBlank()) {
                            prefs.saveTokens(accessToken, refreshToken)
                            LoggerConfig.newSession()
                            AppLogger.onUserLoggedIn()
//                            prefs.setHl7Enabled(isHL7Enabled)
                            // Anchor the offline-expiry clock at login-success so the first-launch
                            // scenario (fresh install + immediate network loss) still expires cleanly
                            // even though /health has not yet succeeded.
                            sessionHealthController.markLoggedIn()
                            logger.i("Access and refresh tokens saved securely.")
                        } else {
                            logger.e("Missing access or refresh token in response.", event = LogEvent.OTP_VERIFICATION_FAILED)
                        }

                        prefs.setLoggedInEmail(user?.email ?: email)
                        prefs.setRegion(loggedInRegion)

                        // Log user info (safely)
                        user?.let {
                            logger.i("User verified: verified=${it.isVerified}, role=${it.role}")
                        } ?: logger.w("User object is null in response.")

                        // Identity first, so LOGIN_SUCCEEDED and everything after it carry the new user.
                        val loggedInEmail = user?.email ?: email
                        auditLogger.onSessionStarted(userId = user?.userId, email = loggedInEmail)
                        auditLogger.log(
                            LoginSucceeded(
                                userId = user?.userId,
                                email = loggedInEmail,
                                role = user?.role,
                                deviceKey = deviceKey,
                                appVersion = AuditLogger.APP_VERSION,
                                rememberMe = rememberMe
                            )
                        )

                        _uiState.value = VerifyPinUiState.Success
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.e("Failed to persist session after successful OTP verification", e, event = LogEvent.OTP_VERIFICATION_FAILED)
                        _uiState.value = VerifyPinUiState.Error(context.getString(R.string.error_unknown))
                    }
                }
                .onFailure { exception ->
                    auditLogger.log(
                        OtpVerifyFailed(
                            email = email,
                            httpStatus = httpStatusOf(exception),
                            errorCode = authErrorCodeOf(exception)
                        )
                    )
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
                        logger.w("Error parsing error response", e, event = LogEvent.OTP_VERIFICATION_FAILED)
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

    /** Stores the Login screen's "Remember me" choice once the OTP is verified. */
    fun saveRememberMe(enabled: Boolean) {
        prefs.setRememberMe(enabled)
    }
}
