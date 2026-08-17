package com.rite.pillcounting.feature.otp.presentation.compose

import Screen
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.rite.pillcounting.R
import com.rite.pillcounting.core.utils.common.HelperFunctions.maskEmail
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.ActionButtonPrimary
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.AppInfo
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.BackButton
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.CommonDialog
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.LoadingIndicator
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.OTPTextField
import com.rite.pillcounting.core.utils.compose.SplitResponsive
import com.rite.pillcounting.feature.login.presentation.viewmodel.LoginViewModel
import com.rite.pillcounting.feature.verifyPin.domain.model.VerifyPinUiState
import com.rite.pillcounting.feature.verifyPin.presentation.viewmodel.VerifyPinViewModel
import com.rite.pillcounting.ui.theme.AppTheme
import kotlinx.coroutines.delay

/**
 * Composable function displaying the OTP verification screen.
 *
 * This screen handles OTP input, countdown timer for resending OTP,
 * and navigation to the dashboard upon successful verification.
 *
 * @param navController NavController used for screen navigation.
 * @param userEmail The email address to which the OTP was sent.
 * @param rememberMe Flag indicating whether the user opted to be remembered.
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .background(AppTheme.extendedColors.secondaryBackground)
    ) {
        BackButton(
            navController = navController,
            onClick = { showExitConfirmationDialog = true }
        )

        SplitResponsive(
            topOrLeft = { AppInfo() },
            bottomOrRight = {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "${stringResource(R.string.code_sent_to)} \n$maskedEmail",
                        color = AppTheme.extendedColors.textColor,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(50.dp))

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
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }

                    Spacer(Modifier.height(40.dp))

                    Text(
                        text = if (isTimerRunning) stringResource(
                            R.string.pre_resend_code,
                            secondsRemaining
                        ) else stringResource(R.string.resend_code),
                        color = if (isTimerRunning) AppTheme.extendedColors.textColor else MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.bodyMedium,
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

                    Spacer(Modifier.height(20.dp))

                    when (val state = verifyPinUiState) {
                        is VerifyPinUiState.Idle -> Spacer(Modifier.height(20.dp))

                        is VerifyPinUiState.Error -> Text(
                            text = state.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        is VerifyPinUiState.Loading -> {
                            LoadingIndicator()
                        }

                        is VerifyPinUiState.Success -> {
                            LaunchedEffect(Unit) {
                                rememberMe.takeIf { it }?.let { viewModel.setUserLoggedIn(true) }
                                otp = ""
                                secondsRemaining = timerDuration
                                isTimerRunning = true

                                navController.navigate(Screen.Dashboard.route) {
                                    popUpTo(Screen.Dashboard.route) { inclusive = true }
                                }
                                viewModel.clearAfterSuccess()

                                if(loginViewModel.preferenceHelper.isUserLoggedIn()){
                                    onLogin()
                                }
                            }
                        }
                    }
                    ActionButtonPrimary(
                        text = stringResource(R.string.verify).uppercase(),
                        onClick = {
                            if (isOtpComplete && verifyPinUiState !is VerifyPinUiState.Loading) {
                                viewModel.verifyPin(userEmail, otp)
                            }
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        enabled = isOtpComplete && verifyPinUiState !is VerifyPinUiState.Loading
                    )
                }
            }
        )
    }
}
