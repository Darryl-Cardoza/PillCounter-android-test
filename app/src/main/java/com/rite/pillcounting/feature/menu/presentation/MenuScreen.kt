package com.rite.pillcounting.feature.menu.presentation

import Screen
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.rite.pillcounting.R
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.room.models.enums.ScanType
import com.rite.pillcounting.core.utils.common.HistoryRetention
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.BackButton
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.CommonDialog
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.LoadingIndicator
import com.rite.pillcounting.core.utils.constants.Dimens.medium
import com.rite.pillcounting.core.room.models.enums.ScanType
import com.rite.pillcounting.core.utils.common.navigateSafely
import com.rite.pillcounting.feature.history.domain.model.HistoryMode
import com.rite.pillcounting.feature.login.domain.model.LogoutUiState
import com.rite.pillcounting.feature.login.viewmodel.LoginViewModel
import com.rite.pillcounting.feature.menu.presentation.compose.MenuItemRow
import com.rite.pillcounting.feature.menu.presentation.compose.SimpleMenuRow
import com.rite.pillcounting.feature.menu.presentation.viewmodel.MenuViewModel
import com.rite.pillcounting.navigation.AUTH_GRAPH_ROUTE
import com.rite.pillcounting.ui.theme.AppTheme
import com.rite.pillcounting.ui.theme.AppTheme.extendedColors

/**
 * Renders the **Menu Screen**, which serves as the main navigation hub for the application's
 * key operations — including count management, user profile access, history, settings, and logout.
 * ---
 * @param navController The [NavController] used for in-app navigation between screens.
 * @param viewModel The [MenuViewModel] managing count-related data displayed in the menu.
 * @param loginViewModel The [LoginViewModel] used to handle logout API requests and state.
 *
 * @see MenuItemRow For menu items with completion/partial count indicators.
 * @see SimpleMenuRow For single-action menu rows like Profile, History, and Logout.
 */
@Composable
fun MenuScreen(
    navController: NavController,
    viewModel: MenuViewModel = hiltViewModel(),
    loginViewModel: LoginViewModel = hiltViewModel(),
    onLogOut:()-> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val logoutState by loginViewModel.logoutUiState.collectAsState()
    var showLogoutLoading by remember { mutableStateOf(false) }
    var showLogoutConfirmDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(extendedColors.secondaryBackground)
    ) {
        BackButton(navController)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = medium, end = medium)
                .background(extendedColors.secondaryBackground)
        ) {
            // Fixed Count
            MenuItemRow(
                icon = R.drawable.pilliconformenuscreen,
                title = stringResource(R.string.dispense),
                completed = stringResource(R.string.menu_completed, uiState.fixedCompleted),
                partial = stringResource(R.string.menu_partial, uiState.fixedPartial),
                iconTint = MaterialTheme.colorScheme.primary,
                completedTint = MaterialTheme.colorScheme.primary,
                partialTint = MaterialTheme.colorScheme.primary,
                completedIcon = R.drawable.complete,
                partialIcon = R.drawable.partial,
                mainClick = { navController.navigate(Screen.ScanBarcode.createRoute(CountType.FIXED.toString(),
                    ScanType.RX_LABEL,0)) },
                onPartialClick = {
                    if (uiState.fixedPartial > 0)
                        navController.navigate(
                            Screen.ResumeFixedCounts.createRoute(
                                CountType.FIXED.toString()
                            )
                        )
                },
                onCompletedClick = {  navController.navigate(Screen.History.createRoute(HistoryMode.DISPENSE)) },
            )

            HorizontalDivider(color = AppTheme.extendedColors.primaryBackground)

            // Regular Count
            MenuItemRow(
                icon = R.drawable.medicationconformenuscreen,
                title = stringResource(R.string.stock_count),
                completed = stringResource(R.string.menu_completed, uiState.regularCompleted),
                partial = stringResource(R.string.menu_partial, uiState.regularPartial),
                iconTint = MaterialTheme.colorScheme.primary,
                completedTint = MaterialTheme.colorScheme.primary,
                partialTint = MaterialTheme.colorScheme.primary,
                completedIcon = R.drawable.complete,
                partialIcon = R.drawable.partial,
                mainClick = { navController.navigate(Screen.ScanBarcode.createRoute(CountType.REGULAR.toString(),ScanType.RX_LABEL,0)) },
                onPartialClick = {
                    if (uiState.regularPartial > 0)
                        navController.navigate(
                            Screen.ResumeRegularCounts.createRoute(
                                CountType.REGULAR.toString()
                            )
                        )
                },
                onCompletedClick = {  navController.navigate(Screen.History.createRoute(HistoryMode.REGULAR)) },
            )

            HorizontalDivider(color = AppTheme.extendedColors.primaryBackground)

            // Load options from strings.xml
            val historyOptions = stringArrayResource(R.array.history_options).toList()

            // Get the saved history retention (number of days)
            val selectedOptionDays = viewModel.getSavedHistoryOption()

            // Map the number of days to a display string
            val trailingText = HistoryRetention.getTrailingText(selectedOptionDays, historyOptions)


            // History
            SimpleMenuRow(
                navController = navController,
                icon = R.drawable.history,
                iconTint = MaterialTheme.colorScheme.primary,
                title = stringResource(R.string.menu_history),
                trailingText = trailingText,
                onClick = { navController.navigateSafely(
                    Screen.History.createRoute(HistoryMode.NORMAL)
                ) }
            )

            HorizontalDivider(color = AppTheme.extendedColors.primaryBackground)
            SimpleMenuRow(
                navController = navController,
                icon = R.drawable.unsynced_transaction_icon,
                iconTint = MaterialTheme.colorScheme.primary,
                title = stringResource(R.string.menu_unsync_transaction),
                trailingText = uiState.unsyncedTransactionCount.toString(),
                onClick = { navController.navigate(Screen.UnsyncedTransactionScreen.route) }
            )

            HorizontalDivider(color = AppTheme.extendedColors.primaryBackground)

            // Settings
            SimpleMenuRow(
                navController = navController,
                icon = R.drawable.settings,
                iconTint = MaterialTheme.colorScheme.primary,
                title = stringResource(R.string.menu_settings),
                onClick = { navController.navigate(Screen.Settings.route) }
            )

            HorizontalDivider(color = AppTheme.extendedColors.primaryBackground)

            SimpleMenuRow(
                navController = navController,
                icon = R.drawable.profile,
                iconTint = MaterialTheme.colorScheme.primary,
                title = stringResource(R.string.menu_profile),
                onClick = { navController.navigate(Screen.Profile.route) }
            )
            HorizontalDivider(color = AppTheme.extendedColors.primaryBackground)
            // Logout
            SimpleMenuRow(
                navController = navController,
                icon = R.drawable.logout,
                iconTint = MaterialTheme.colorScheme.primary,
                title = stringResource(R.string.menu_logout),
                onClick = {
                    showLogoutConfirmDialog = true
                }
            )

            if (showLogoutConfirmDialog) {
                CommonDialog(
                    message = stringResource(R.string.confirm_logout_text),
                    title = stringResource(R.string.confirm_logout_title),
                    confirmText = stringResource(R.string.menu_logout),
                    cancelText = stringResource(R.string.cancel),
                    onConfirm = {
                        showLogoutLoading = true
                        val refreshToken = loginViewModel.preferenceHelper.getRefreshToken()
                        if (!refreshToken.isNullOrBlank()) {
                            loginViewModel.logout(refreshToken)
                        } else {
                            // Fallback: clear session locally if token missing
                            loginViewModel.preferenceHelper.clearTokens()
                            loginViewModel.preferenceHelper.setUserLoggedIn(false)
                            showLogoutLoading = false
                            navController.navigate(AUTH_GRAPH_ROUTE) {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                        showLogoutConfirmDialog = false
                    },
                    onCancel = { showLogoutConfirmDialog = false }
                )
            }

            // Logout UI Feedback
            when (val state = logoutState) {
                is LogoutUiState.Loading -> {
                    if (showLogoutLoading) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = 40.dp)
                        ) {
                            LoadingIndicator()
                            Text(
                                text = stringResource(R.string.logging_out),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }

                is LogoutUiState.Error -> {
                    showLogoutLoading = false
                    Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }

                is LogoutUiState.Success -> {
                    LaunchedEffect(Unit) {
                        // Clear user session on successful logout
                        loginViewModel.clearSession()
                        loginViewModel.clearAllStates()
                        showLogoutLoading = false

                        // Navigate back to login/auth graph
                        navController.navigate(AUTH_GRAPH_ROUTE) {
                            popUpTo(0) { inclusive = true }
                        }

                        if(!loginViewModel.preferenceHelper.isUserLoggedIn()){
                            onLogOut()
                        }
                    }
                }

                else -> {}
            }
        }
    }
}
