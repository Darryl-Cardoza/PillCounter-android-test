package com.rite.pillcounting.feature.login.presentation

import Screen
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ArrowDropUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.rite.pillcounting.R
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.ActionButtonPrimary
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.AppInfo
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.DrawableIconTextField
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.LoadingIndicator
import com.rite.pillcounting.core.utils.compose.SplitResponsive
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.login.domain.model.LoginUiState
import com.rite.pillcounting.feature.login.presentation.viewmodel.LoginViewModel
import com.rite.pillcounting.ui.theme.AppTheme

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
    val context = LocalContext.current
    val preferenceHelper = remember { PreferenceHelper(context) }
    var showDropdown by remember { mutableStateOf(false) }
    val recentEmails = remember { mutableStateOf(preferenceHelper.getRecentLogins()) }
    val loginUiState by viewModel.uiState.collectAsState()
    var textFieldSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .background(AppTheme.extendedColors.secondaryBackground)
    ) {
        SplitResponsive(
            topOrLeft = { AppInfo() },
            bottomOrRight = {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(65.dp))
                    // Email Input Field
                    Box {
                        DrawableIconTextField(
                            value = email,
                            onValueChange = {
                                email = it
                                localError = null
                                viewModel.resetLoginState()
                            },
                            placeholder = stringResource(R.string.email),
                            iconRes = R.drawable.profile,
                            iconColor = MaterialTheme.colorScheme.secondary,
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Done,
                            trailingIcon = {
                                IconButton(onClick = { showDropdown = !showDropdown }) {
                                    Icon(
                                        imageVector = if (showDropdown)
                                            Icons.Outlined.ArrowDropUp
                                        else Icons.Outlined.ArrowDropDown,
                                        contentDescription = "Toggle recent logins",
                                        modifier = Modifier.size(28.dp),
                                        tint = AppTheme.extendedColors.textColor
                                    )
                                }
                            },
                            modifier = Modifier.onGloballyPositioned { coords ->
                                textFieldSize = coords.size.toSize()
                            }
                        )

                        if (showDropdown) {
                            DropdownMenu(
                                containerColor = AppTheme.extendedColors.inputBackground,
                                expanded = showDropdown,
                                onDismissRequest = { showDropdown = false },
                                modifier = Modifier
                                    .width(with(LocalDensity.current) { textFieldSize.width.toDp() })
                                    .background(
                                        AppTheme.extendedColors.inputBackground,
                                        RoundedCornerShape(12.dp)
                                    )
                                    .border(
                                        0.0.dp,
                                        AppTheme.extendedColors.inputBackground,
                                        RoundedCornerShape(12.dp)
                                    )
                                    .padding(horizontal = 8.dp)
                                    .padding(top = 16.dp)
                            ) {
                                if (recentEmails.value.isEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "No recent logins",
                                            color = Color.Gray,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                } else {
                                    recentEmails.value.forEachIndexed { index, emailEntry ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 8.dp)
                                                .background(Color.Transparent)
                                                .clickable {
                                                    email = emailEntry
                                                    showDropdown = false
                                                },
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = emailEntry,
                                                color = AppTheme.extendedColors.textColor,
                                                style = MaterialTheme.typography.bodyMedium
                                            )

                                            IconButton(
                                                onClick = {
                                                    preferenceHelper.removeRecentLogin(
                                                        emailEntry
                                                    )
                                                    recentEmails.value =
                                                        preferenceHelper.getRecentLogins()
                                                },
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .padding(end = 8.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Close,
                                                    contentDescription = "Remove email",
                                                    tint = AppTheme.extendedColors.textColor
                                                )
                                            }
                                        }

                                        if (index != recentEmails.value.lastIndex) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(0.5.dp)
                                                    .background(
                                                        AppTheme.extendedColors.textColor.copy(
                                                            alpha = 0.2f
                                                        )
                                                    )
                                            )
                                        }
                                    }

                                }
                            }

                        }
                    }

                    // Local validation message (Empty field)
                    if (!localError.isNullOrEmpty()) {
                        Text(
                            text = localError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .align(Alignment.Start)
                        )
                    }

                    Spacer(Modifier.height(25.dp))

                    // Remember Me Checkbox
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .border(
                                    width = 0.5.dp,
                                    color = AppTheme.extendedColors.textColor,
                                    shape = RoundedCornerShape(3.dp)
                                )
                                .background(
                                    Color.White, shape = RoundedCornerShape(3.dp)
                                )
                        ) {
                            Checkbox(
                                checked = rememberMe,
                                onCheckedChange = { rememberMe = it },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Color.White,
                                    uncheckedColor = Color.White,
                                    checkmarkColor = Color.Black,
                                ),
                                modifier = Modifier.scale(0.8f)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.remember_me),
                            color = AppTheme.extendedColors.textColor,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    // Display login UI state feedback
                    when (val state = loginUiState) {
                        is LoginUiState.Idle -> Spacer(Modifier.height(30.dp))

                        is LoginUiState.Error -> Text(
                            text = state.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        is LoginUiState.Loading -> {
                            LoadingIndicator()
                        }

                        is LoginUiState.Success -> {
                            LaunchedEffect(Unit) {
                                viewModel.clearAllStates()
                                navController.navigate(
                                    Screen.OtpVerify.createRoute(
                                        email = email,
                                        rememberMe = rememberMe
                                    )
                                )
                                email = ""
                                rememberMe = false
                            }
                        }
                    }

                    // LOGIN BUTTON
                    ActionButtonPrimary(
                        text = stringResource(R.string.login_button_text),
                        onClick = {
                            if (email.isBlank()) {
                                localError = context.getString(R.string.error_empty_email)
                            } else {
                                localError = null
                                preferenceHelper.addRecentLogin(email) // Add to recent logins
                                viewModel.login(email)
                            }
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
        )
    }
}

