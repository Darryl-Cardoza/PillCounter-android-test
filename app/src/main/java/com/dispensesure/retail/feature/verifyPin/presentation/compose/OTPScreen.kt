package com.dispensesure.retail.feature.verifyPin.presentation.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.dispensesure.retail.R
import com.dispensesure.retail.core.utils.common.HelperFunctions.maskEmail
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.ActionButtonPrimary
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.BackButton
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.CommonDialog
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.OTPTextField
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.responsiveDp
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.responsiveSp
import com.dispensesure.retail.core.utils.compose.AuthCard
import com.dispensesure.retail.core.utils.compose.authCardBackground
import com.dispensesure.retail.core.utils.compose.GlobalLoadingOverlay
import com.dispensesure.retail.feature.login.presentation.viewmodel.LoginViewModel
import com.dispensesure.retail.feature.verifyPin.domain.model.VerifyPinUiState
import com.dispensesure.retail.feature.verifyPin.presentation.viewmodel.VerifyPinViewModel
import com.dispensesure.retail.navigation.Screen
import com.dispensesure.retail.ui.theme.AppTheme
import kotlinx.coroutines.delay

/**
 * Composable function displaying the OTP verification screen.
 *
 * This screen handles OTP input, countdown timer for resending OTP,
 * and navigation to the dashboard upon successful verification.
 *
 * @param navController NavController used for screen navigation.
 * @param userEmail The email address to which the OTP was sent.
 * @param rememberMe The Login screen's "Remember me" choice, saved once the OTP is verified.
 * @param viewModel [VerifyPinViewModel] scoped to this screen for OTP verification logic.
 * @param loginViewModel [LoginViewModel] to trigger resend OTP actions.
 */
@Composable
fun OTPScreen(
    navController: NavController,
    userEmail: String,
    rememberMe: Boolean,
    viewModel: VerifyPinViewModel = hiltViewModel(),
    loginViewModel: LoginViewModel = hiltViewModel(),
    onLogin: () -> Unit
) {
    var otp by remember { mutableStateOf("") }
    val verifyPinUiState by viewModel.uiState.collectAsState()
    val isVerifying = verifyPinUiState is VerifyPinUiState.Loading
    val textColor = AppTheme.extendedColors.textColor

    // Timer settings
    val timerDuration = 60
    var secondsRemaining by remember { mutableIntStateOf(timerDuration) }
    var isTimerRunning by remember { mutableStateOf(true) }
    var showExitConfirmationDialog by remember { mutableStateOf(false) }
    val isOtpComplete = otp.length == 6

    // Mask the email for privacy display
    val maskedEmail = remember(userEmail) { maskEmail(userEmail) }

    // Countdown timer effect
    LaunchedEffect(isTimerRunning) {
        while (isTimerRunning && secondsRemaining > 0) {
            delay(1000L)
            secondsRemaining -= 1
        }
        if (secondsRemaining == 0) {
            isTimerRunning = false
        }
    }

    if (verifyPinUiState is VerifyPinUiState.Success) {
        LaunchedEffect(Unit) {
            viewModel.saveRememberMe(rememberMe)
            viewModel.setUserLoggedIn(true)
            otp = ""
            secondsRemaining = timerDuration
            isTimerRunning = true

            navController.navigate(Screen.Dashboard.route) {
                popUpTo(Screen.Dashboard.route) { inclusive = true }
            }
            viewModel.clearAfterSuccess()

            onLogin()
        }
    }

    if (showExitConfirmationDialog) {
        CommonDialog(
            message = stringResource(R.string.confirm_exit_message),
            title = stringResource(R.string.confirm_exit_title),
            confirmText = stringResource(R.string.yes),
            cancelText = stringResource(R.string.no),
            onConfirm = {
                showExitConfirmationDialog = false
                navController.popBackStack()
            },
            onCancel = { showExitConfirmationDialog = false }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AuthCard(footerText = stringResource(R.string.contact_support_for_code)) {
            Text(
                text = stringResource(R.string.code_sent_to),
                color = textColor.copy(alpha = 0.7f),
                fontSize = responsiveSp(8.sp),
                textAlign = TextAlign.Center,
            )
            Text(
                text = maskedEmail,
                color = textColor,
                fontSize = responsiveSp(8.sp),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(responsiveDp(20.dp)))

            (verifyPinUiState as? VerifyPinUiState.Error)?.let { error ->
                Text(
                    text = error.message,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = responsiveSp(7.sp),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = responsiveDp(8.dp))
                )
            }

            val otpBoxCount = 6
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                // Fit boxCount boxes (+ spacing) within the available width so small
                // phones in portrait or landscape don't overflow.
                val maxBoxSize = 56.dp
                val minBoxSize = 36.dp
                val spacing = 12.dp
                val availableWidth = maxWidth
                val computedBoxSize =
                    ((availableWidth - spacing * (otpBoxCount - 1)) / otpBoxCount)
                        .coerceIn(minBoxSize, maxBoxSize)
                val computedSpacing = if (computedBoxSize == minBoxSize) {
                    ((availableWidth - minBoxSize * otpBoxCount) / (otpBoxCount - 1))
                        .coerceAtLeast(4.dp)
                } else {
                    spacing
                }

                OTPTextField(
                    otp = otp,
                    onOtpChange = {
                        otp = it
                        viewModel.resetState()
                    },
                    boxCount = otpBoxCount,
                    boxSize = computedBoxSize,
                    spacing = computedSpacing,
                    boxBackground = authCardBackground(),
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            Spacer(Modifier.height(responsiveDp(16.dp)))

            Text(
                text = if (isTimerRunning) stringResource(
                    R.string.pre_resend_code,
                    secondsRemaining
                ) else stringResource(R.string.resend_code),
                color = if (isTimerRunning) textColor.copy(alpha = 0.7f) else MaterialTheme.colorScheme.primary,
                fontSize = responsiveSp(7.sp),
                modifier = if (!isTimerRunning) {
                    Modifier.clickable {
                        loginViewModel.login(userEmail)
                        secondsRemaining = timerDuration
                        isTimerRunning = true
                    }
                } else {
                    Modifier
                }
            )

            Spacer(Modifier.height(responsiveDp(20.dp)))

            ActionButtonPrimary(
                text = stringResource(R.string.verify),
                onClick = {
                    if (isOtpComplete && !isVerifying) {
                        viewModel.verifyPin(userEmail, otp)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = isOtpComplete && !isVerifying,
                fixedWidth = false,
            )
        }

        // Drawn after the card so it sits on top, clear of the status bar.
        BackButton(
            navController = navController,
            onClick = { showExitConfirmationDialog = true },
            modifier = Modifier
                .systemBarsPadding()
                .displayCutoutPadding()
        )

        GlobalLoadingOverlay(isVisible = isVerifying)
    }
}
