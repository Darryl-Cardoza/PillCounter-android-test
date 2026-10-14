package com.dispensesure.retail.feature.login.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.dispensesure.retail.R
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.ActionButtonPrimary
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.responsiveDp
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.responsiveSp
import com.dispensesure.retail.core.utils.compose.AuthCard
import com.dispensesure.retail.core.utils.compose.AuthEmailField
import com.dispensesure.retail.core.utils.compose.GlobalLoadingOverlay
import com.dispensesure.retail.feature.login.domain.model.LoginUiState
import com.dispensesure.retail.feature.login.presentation.viewmodel.LoginViewModel
import com.dispensesure.retail.navigation.Screen
import com.dispensesure.retail.ui.theme.AppTheme

/**
 * Composable that renders the Login Screen UI.
 *
 * It follows a unidirectional data flow by observing states from [LoginViewModel]
 * and delegating user actions to it.
 *
 * @param navController NavController for screen navigation.
 * @param viewModel LoginViewModel instance scoped to this screen.
 */
@Composable
fun LoginScreen(
    navController: NavController,
    viewModel: LoginViewModel = hiltViewModel()
) {
    var email by remember { mutableStateOf("") }
    var rememberMe by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    val loginUiState by viewModel.uiState.collectAsState()
    val isLoading = loginUiState is LoginUiState.Loading
    val errorMessage = localError ?: (loginUiState as? LoginUiState.Error)?.message
    val textColor = AppTheme.extendedColors.textColor
    val emptyEmailError = stringResource(R.string.error_empty_email)

    // Shared by the Log In button and the keyboard's Done key.
    val submitLogin = {
        if (!isLoading) {
            if (email.isBlank()) {
                localError = emptyEmailError
            } else {
                localError = null
                viewModel.login(email)
            }
        }
    }

    if (loginUiState is LoginUiState.Success) {
        LaunchedEffect(Unit) {
            viewModel.clearAllStates()
            navController.navigate(
                Screen.OtpVerify.createRoute(email = email, rememberMe = rememberMe)
            )
            email = ""
            rememberMe = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AuthCard(footerText = stringResource(R.string.contact_administrator_for_access)) {
            Text(
                text = stringResource(R.string.welcome_back),
                color = textColor,
                fontSize = responsiveSp(12.sp),
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(responsiveDp(4.dp)))
            Text(
                text = stringResource(
                    R.string.sign_in_to_continue_to,
                    stringResource(R.string.pill_count_app_title)
                ),
                color = textColor.copy(alpha = 0.7f),
                fontSize = responsiveSp(8.sp),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(responsiveDp(20.dp)))

            // Empty-email and server errors share one slot above the field.
            if (errorMessage != null) {
                Text(
                    text = errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = responsiveSp(7.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = responsiveDp(8.dp))
                )
            }

            AuthEmailField(
                value = email,
                onValueChange = {
                    email = it
                    localError = null
                    viewModel.resetLoginState()
                },
                onDone = submitLogin
            )

            Spacer(Modifier.height(responsiveDp(12.dp)))

            RememberMeCheckbox(
                checked = rememberMe,
                onCheckedChange = { rememberMe = it }
            )

            Spacer(Modifier.height(responsiveDp(20.dp)))

            ActionButtonPrimary(
                text = stringResource(R.string.log_in),
                onClick = submitLogin,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading,
                fixedWidth = false,
            )
        }

        GlobalLoadingOverlay(isVisible = isLoading)
    }
}

/** "Remember me" checkbox; only the box itself is tappable. */
@Composable
private fun RememberMeCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val textColor = AppTheme.extendedColors.textColor
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = MaterialTheme.colorScheme.primary,
                uncheckedColor = textColor.copy(alpha = 0.4f),
                checkmarkColor = Color.White,
            ),
            modifier = Modifier.size(responsiveDp(20.dp))
        )
        Spacer(Modifier.width(responsiveDp(8.dp)))
        Text(
            text = stringResource(R.string.remember_me),
            color = textColor,
            fontSize = responsiveSp(7.sp),
        )
    }
}
