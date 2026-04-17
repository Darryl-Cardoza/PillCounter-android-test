package com.rite.pillcounting.feature.dashboard.presentation

import Screen
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.rite.pillcounting.R
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.room.models.enums.ScanType
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.CommonDialog
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.MenuButton
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.PmsConnectionIcon
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.showToast
import com.rite.pillcounting.core.utils.compose.SplitResponsive
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.dashboard.presentation.compose.FixedCountSection
import com.rite.pillcounting.feature.dashboard.presentation.compose.RegularCountSection
import com.rite.pillcounting.feature.dashboard.presentation.viewmodel.DashboardViewModel
import com.rite.pillcounting.navigation.AUTH_GRAPH_ROUTE
import com.rite.pillcounting.ui.theme.AppTheme

/**
 * Dashboard screen entry point.
 *
 * Displays:
 * - [FixedCountSection] on the top/left.
 * - [RegularCountSection] on the bottom/right.
 * - [MenuButton] for navigation.
 *
 * The layout adapts responsively based on available space via [SplitResponsive].
 *
 * Handles:
 * - Exit confirmation dialog on back press.
 * - Navigation to Profile screen if profile is incomplete (unless "Do not ask again" is set).
 *
 * @param navController Used for navigation actions from dashboard sections.
 * @param viewModel ViewModel responsible for providing dashboard data/state.
 */
@Composable
fun DashboardScreen(
    navController: NavController,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    var showLogoutDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val activity = context as? Activity
    val preferenceHelper = remember { PreferenceHelper(context) }

    // Prevent navigating back from dashboard screen
    BackHandler(enabled = true) {
        showLogoutDialog = true
    }

    // Collect dashboard UI state reactively
    val uiState by viewModel.uiState.collectAsState()
    val connected by viewModel.isConnected.collectAsState()

    // Handle navigation to Profile screen if profile is incomplete
    LaunchedEffect(uiState.navigateToProfile) {
        val isProfileChecked = preferenceHelper.isProfileChecked()

        if (!isProfileChecked) {
            if (uiState.navigateToProfile && !preferenceHelper.isDoNotAskAgain()) {
                println("Navigating to Profile screen")
                navController.navigate(Screen.Profile.route)
                viewModel.resetNavigateToProfile()
                preferenceHelper.setProfileChecked(true)
            }
        } else {
            println("Profile check already completed, not navigating.")
        }
    }
    LaunchedEffect(uiState.logoutUser) {
        if (uiState.logoutUser) {
            showToast(context, R.string.session_expired)
            navController.navigate(AUTH_GRAPH_ROUTE) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    LaunchedEffect(uiState.createdBatchId) {
        uiState.createdBatchId?.let { batchId ->
            navController.navigate(
                Screen.ScanBarcode.createRoute(
                    txnScanType = ScanType.STOCK_COUNT,
                    scanType = CountType.REGULAR.toString(),
                    batchId = batchId
                )
            )
            viewModel.clearCreatedBatchId()
        }
    }

    Box(
        modifier = Modifier
            .systemBarsPadding()
            .background(AppTheme.extendedColors.secondaryBackground)
    ) {

        // Show loading indicator if user details are being fetched
        if (viewModel.isHl7Enabled()) {
            PmsConnectionIcon(
                modifier = Modifier.align(Alignment.TopStart),
                isPmsConnected = connected,
            )
        }

        SplitResponsive(
            topOrLeft = {
                FixedCountSection(
                    completedFixedCount = uiState.completedFixedCount,
                    partialFixedCount = uiState.partialFixedCount,
                    navController = navController,
                    onNavigate = { viewModel.saveTxnId() }
                )
            },
            bottomOrRight = {
                RegularCountSection(
                    completedRegularCount = uiState.completedRegularCount,
                    partialRegularCount = uiState.partialRegularCount,
                    navController = navController,
                    onNavigate = { viewModel.saveTxnId() },
                    viewModel = viewModel
                )
            }
        )

        // Global navigation menu button (top-right aligned)
        MenuButton(
            navController,
            modifier = Modifier.align(Alignment.TopEnd)
        )
    }

    // Exit confirmation dialog
    if (showLogoutDialog) {
        CommonDialog(
            message = stringResource(R.string.exit_text),
            confirmText = stringResource(R.string.yes),
            cancelText = stringResource(R.string.no),
            onConfirm = {
                showLogoutDialog = false
                activity?.finishAffinity()
            },
            onCancel = { showLogoutDialog = false }
        )
    }
}