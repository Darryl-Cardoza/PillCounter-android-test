package com.rite.pillcounting.feature.dashboard.presentation

import Screen
import android.app.Activity
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.rite.pillcounting.R
import com.rite.pillcounting.core.room.models.enums.CountType
import android.widget.Toast
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.CommonDialog
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.CommonSingleSelectDialog
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.core.settings.presentation.viewmodel.MainActivityViewModel
import com.rite.pillcounting.feature.dashboard.domain.model.DashboardTab
import com.rite.pillcounting.feature.dashboard.presentation.variant.DashboardPhoneLandscape
import com.rite.pillcounting.feature.dashboard.presentation.variant.DashboardPhonePortrait
import com.rite.pillcounting.feature.dashboard.presentation.variant.DashboardTabletLandscape
import com.rite.pillcounting.feature.dashboard.presentation.variant.DashboardTabletPortrait
import com.rite.pillcounting.feature.dashboard.presentation.variant.DashboardVariantParams
import com.rite.pillcounting.feature.dashboard.presentation.viewmodel.DashboardViewModel
import com.rite.pillcounting.navigation.AUTH_GRAPH_ROUTE

/**
 * Dashboard screen entry point.
 *
 * Acts as a thin dispatcher:
 *  - Collects state from [DashboardViewModel]
 *  - Owns global side-effects (back-press → exit dialog, profile redirect, logout, batch-created nav,
 *    HL7 start once terminal info is loaded)
 *  - Selects a variant composable based on window form factor + orientation
 *
 * The four variants live under [com.rite.pillcounting.feature.dashboard.presentation.variant] and
 * all accept the same [DashboardVariantParams]. This enforces "same data, different placement"
 * at the type level.
 *
 * Variant selection uses [Configuration.smallestScreenWidthDp] (≥600dp = tablet) plus the current
 * orientation. This avoids pulling in the material3-window-size-class artifact for what is, in
 * practice, a binary form-factor decision.
 */
@Composable
fun DashboardScreen(
    navController: NavController,
    viewModel: DashboardViewModel = hiltViewModel(),
    mainActivityViewModel: MainActivityViewModel = hiltViewModel(),
) {
    var showLogoutDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val activity = context as? Activity
    val preferenceHelper = remember { PreferenceHelper(context) }

    BackHandler(enabled = true) {
        showLogoutDialog = true
    }

    val uiState by viewModel.uiState.collectAsState()
    val connected by viewModel.isConnected.collectAsState()
    val terminalInfoLoaded by viewModel.terminalInfoLoaded.collectAsState()

    LaunchedEffect(terminalInfoLoaded) {
        if (terminalInfoLoaded) {
            mainActivityViewModel.startHl7AfterTerminalLoaded()
        }
    }

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
                Screen.DispenseFlow.createRoute(
                    scanType = CountType.REGULAR.toString(),
                    batchId = batchId,
                )
            )
            viewModel.clearCreatedBatchId()
        }
    }

    // Inventory quick action: bucket selected but no BatchEntity yet — the batch is
    // created on the first NDC scan inside InventoryScanViewModel.
    LaunchedEffect(uiState.pendingStockCountBucketId) {
        uiState.pendingStockCountBucketId?.let { bucketId ->
            navController.navigate(
                Screen.DispenseFlow.createRoute(
                    scanType = CountType.REGULAR.toString(),
                    bucketId = bucketId,
                )
            )
            viewModel.clearPendingStockCountBucketId()
        }
    }

    // Inventory Quick Action goes straight to bucket-select: clicking Inventory always
    // creates a new batch. (Resume-last is reachable from elsewhere if needed.)
    var showBucketSelectDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // ── Build the params bag shared by every variant ──
    // Lambdas are remembered so [DashboardVariantParams] is referentially stable across
    // recompositions — variants and their LazyColumn rows can skip re-render when only
    // `uiState` / `isPmsConnected` change.
    val onKpiFilterTapped = remember(viewModel) { viewModel::onKpiFilterTapped }
    val onTabSelected = remember(viewModel) { viewModel::onTabSelected }
    val onDispenseQuickAction = remember(viewModel, navController) {
        {
            viewModel.saveTxnId()
            navController.navigate(
                Screen.DispenseFlow.createRoute(scanType = CountType.FIXED.toString())
            )
        }
    }
    val onInventoryQuickAction = remember(viewModel) {
        {
            viewModel.saveTxnId()
            showBucketSelectDialog = true
        }
    }
    val onRecentDispenseClick = remember(viewModel, navController) {
        { txnId: Long ->
            viewModel.selectCurrentTransaction(txnId)
            navController.navigate(Screen.HistoryDetail.route)
            Unit
        }
    }
    val onRecentBatchClick = remember(navController) {
        { batchId: Long ->
            navController.navigate(Screen.BatchHistoryDetail.createRoute(batchId))
            Unit
        }
    }
    val onQueueDispenseClick = remember(viewModel, navController) {
        { txnId: Long ->
            viewModel.selectCurrentTransaction(txnId)
            navController.navigate(
                Screen.DispenseFlow.createRoute(
                    scanType = CountType.FIXED.toString(),
                    fromResume = true,
                )
            )
            Unit
        }
    }
    val onQueueInventoryClick = remember(navController) {
        { batchId: Long ->
            navController.navigate(Screen.InventoryScan.createRoute(batchId))
            Unit
        }
    }

    val params = DashboardVariantParams(
        uiState = uiState,
        isPmsConnected = connected,
        isHl7Enabled = viewModel.isHl7Enabled(),
        navController = navController,
        onKpiFilterTapped = onKpiFilterTapped,
        onTabSelected = onTabSelected,
        onDispenseQuickAction = onDispenseQuickAction,
        onInventoryQuickAction = onInventoryQuickAction,
        onRecentDispenseClick = onRecentDispenseClick,
        onRecentBatchClick = onRecentBatchClick,
        onQueueDispenseClick = onQueueDispenseClick,
        onQueueInventoryClick = onQueueInventoryClick,
    )

    if (showBucketSelectDialog) {
        val bucketList = viewModel.getBucketList()
        val defaultIndex = bucketList.indices.firstOrNull() ?: -1
        CommonSingleSelectDialog(
            title = stringResource(R.string.select_bucket),
            options = bucketList,
            selectedIndex = defaultIndex,
            onCancel = { showBucketSelectDialog = false },
            onOk = { index ->
                if (index in bucketList.indices) {
                    viewModel.createBatch(bucketList[index])
                }
                showBucketSelectDialog = false
            },
            distanceBetweenOptions = 2.dp,
        )
    }

    // ── Dispatch to the right variant ──
    val config = LocalConfiguration.current
    val isTablet = config.smallestScreenWidthDp >= TABLET_BREAKPOINT_DP
    val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE

    when {
        isTablet && !isLandscape -> DashboardTabletPortrait(params)
        isTablet && isLandscape -> DashboardTabletLandscape(params)
        !isTablet && !isLandscape -> DashboardPhonePortrait(params)
        else -> DashboardPhoneLandscape(params)
    }

    if (showLogoutDialog) {
        CommonDialog(
            message = stringResource(R.string.exit_text),
            confirmText = stringResource(R.string.yes),
            cancelText = stringResource(R.string.no),
            onConfirm = {
                showLogoutDialog = false
                activity?.finishAffinity()
            },
            onCancel = { showLogoutDialog = false },
        )
    }
}

/** Smallest-width breakpoint that distinguishes tablets from phones (matches Android's `sw600dp` qualifier). */
private const val TABLET_BREAKPOINT_DP = 600
