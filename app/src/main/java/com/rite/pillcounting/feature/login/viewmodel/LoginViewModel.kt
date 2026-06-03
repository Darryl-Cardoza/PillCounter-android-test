package com.rite.pillcounting.feature.login.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rite.pillcounting.R
import com.rite.pillcounting.core.models.ErrorResponse
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.core.utils.common.NetworkUtils
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.login.data.LoginRepository
import com.rite.pillcounting.core.utils.validator.CredentialsValidator
import com.rite.pillcounting.feature.login.domain.model.LoginUiState
import com.rite.pillcounting.feature.login.domain.model.LogoutUiState
import com.google.gson.Gson
import com.rite.pillcounting.core.hl7.service.Hl7serviceHandler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject

/**
 * ViewModel responsible for handling the authentication logic for the login and logout process.
 *
 * This ViewModel:
 * - Validates user credentials.
 * - Manages authentication API interactions via [LoginRepository].
 * - Updates the UI using [LoginUiState] and [LogoutUiState] flows.
 * - Handles persistent login flags through [PreferenceHelper].
 *
 * @param repository Provides access to login/logout backend operations.
 * @param validator Performs email format validation before login.
 * @param context Required to resolve error strings.
 * @param preferenceHelper Utility to persist token and login state.
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val repository: LoginRepository,
    private val validator: CredentialsValidator,
    @ApplicationContext private val context: Context,
    val preferenceHelper: PreferenceHelper,
    val serviceManager: Hl7serviceHandler
) : ViewModel() {

    private val logger = AppLogger.create<LoginViewModel>()

    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val uiState = _uiState.asStateFlow()

    private val _logoutUiState = MutableStateFlow<LogoutUiState>(LogoutUiState.Idle)
    val logoutUiState = _logoutUiState.asStateFlow()

    // -------------------------------------------------------------------------
    // LOGIN FLOW
    // -------------------------------------------------------------------------
    fun login(email: String) {
        val validationResult = validator.validateEmail(email)

        if (!validationResult.isSuccess) {
            val errorMessage = validationResult.errorMessageResId?.let { context.getString(it) }
                ?: context.getString(R.string.error_invalid_email)
            _uiState.value = LoginUiState.Error(errorMessage)
            return
        }

        if (_uiState.value is LoginUiState.Loading) return

        // Use centralized NetworkUtils
        if (!NetworkUtils.isNetworkAvailable(context)) {
            _uiState.value = LoginUiState.Error(context.getString(R.string.error_no_internet))
            return
        }

        viewModelScope.launch {
            logger.i("Login attempt for user: $email")
            _uiState.value = LoginUiState.Loading

            repository.login(email)
                .onSuccess {
                    logger.i("Login successful for user: $email.")
                    _uiState.value = LoginUiState.Success
                    serviceManager.startService()
                }
                .onFailure { exception ->
                    logger.e("Login failed for user: $email", exception)
                    _uiState.value = LoginUiState.Error(getFriendlyErrorMessage(exception))
                }
        }
    }

    // -------------------------------------------------------------------------
    // LOGOUT FLOW
    // -------------------------------------------------------------------------
    fun logout(refreshToken: String) {
        if (_logoutUiState.value is LogoutUiState.Loading) return

        // Use centralized NetworkUtils
        if (!NetworkUtils.isNetworkAvailable(context)) {
            _logoutUiState.value = LogoutUiState.Error(context.getString(R.string.error_no_internet))
            return
        }

        viewModelScope.launch {
            _logoutUiState.value = LogoutUiState.Loading

            repository.logout(refreshToken)
                .onSuccess {
                    logger.i("Logout successful")
                    _logoutUiState.value = LogoutUiState.Success
                    preferenceHelper.clearHl7Config()
                }
                .onFailure { exception ->
                    logger.e("Logout failed", exception)
                    _logoutUiState.value = LogoutUiState.Error(getFriendlyErrorMessage(exception))
                }
        }
    }

    // -------------------------------------------------------------------------
    // STATE RESET HELPERS
    // -------------------------------------------------------------------------
    fun resetLoginState() {
        if (_uiState.value !is LoginUiState.Idle) {
            _uiState.value = LoginUiState.Idle
        }
    }

    fun clearSession(){
        preferenceHelper.clearTokens()
        preferenceHelper.setUserLoggedIn(false)
        preferenceHelper.saveLocalId(0)
    }

    fun clearAllStates() {
        _uiState.value = LoginUiState.Idle
        _logoutUiState.value = LogoutUiState.Idle
    }

    // -------------------------------------------------------------------------
    // FRIENDLY ERROR HANDLING
    // -------------------------------------------------------------------------
    private fun getFriendlyErrorMessage(exception: Throwable): String { //TODO(Shift this to the utils for the helper function)
        return when (exception) {
            is HttpException -> {
                val errorBody = exception.response()?.errorBody()?.string()
                val parsedMessage = errorBody?.let {
                    try {
                        val errorResponse = Gson().fromJson(it, ErrorResponse::class.java)
                        errorResponse.message
                    } catch (e: Exception) {
                        logger.e("Error parsing error response", e)
                        null
                    }
                }
                when (exception.code()) {
                    400 -> parsedMessage ?: context.getString(R.string.error_invalid_email)
                    401 -> context.getString(R.string.error_unauthorized)
                    500 -> context.getString(R.string.error_server_unavailable)
                    else -> parsedMessage ?: context.getString(R.string.error_generic)
                }
            }

            is UnknownHostException -> context.getString(R.string.error_no_internet)
            is SocketTimeoutException -> context.getString(R.string.error_timeout)
            else -> exception.message?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.error_generic)
        }
    }

}
