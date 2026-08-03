package com.rite.pillcounting.feature.dashboard.presentation

import Screen
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.rite.pillcounting.R
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.CommonDialog
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.CommonSingleSelectDialog
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.showToast
import com.rite.pillcounting.core.utils.compose.GlobalLoadingOverlay
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.dashboard.domain.model.KpiFilter
import com.rite.pillcounting.feature.dashboard.presentation.model.DashboardVariantParams
import com.rite.pillcounting.feature.dashboard.presentation.variant.DashboardPhoneLandscape
import com.rite.pillcounting.feature.dashboard.presentation.variant.DashboardPhonePortrait
import com.rite.pillcounting.feature.dashboard.presentation.variant.DashboardTabletLandscape
import com.rite.pillcounting.feature.dashboard.presentation.variant.DashboardTabletPortrait
import com.rite.pillcounting.feature.dashboard.presentation.viewmodel.DashboardViewModel
import com.rite.pillcounting.feature.settings.presentation.viewmodel.MainActivityViewModel
import com.rite.pillcounting.navigation.AUTH_GRAPH_ROUTE

private val logger = AppLogger("DashboardScreen")

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
 * Variant selection uses [UserInterfaceUtils.isTablet] (smallest-width ≥600dp) plus
 * [UserInterfaceUtils.isLandscape]. This avoids pulling in the material3-window-size-class
 * artifact for what is, in practice, a binary form-factor decision.
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
    val pmsCertMismatch by viewModel.pmsCertMismatch.collectAsState()
    val terminalInfoLoaded by viewModel.terminalInfoLoaded.collectAsState()

    // Refresh the top-bar terminal whenever the dashboard resumes — e.g. after
    // returning from Profile, which persists a terminal change to preferences but
    // can't update this screen's state directly.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshTerminalsFromPrefs()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(terminalInfoLoaded) {
        if (terminalInfoLoaded) {
            mainActivityViewModel.startHl7AfterTerminalLoaded()
        }
    }

    LaunchedEffect(uiState.navigateToProfile) {
        val isProfileChecked = preferenceHelper.isProfileChecked()

        if (!isProfileChecked) {
            if (uiState.navigateToProfile && !preferenceHelper.isDoNotAskAgain()) {
                logger.d("Navigating to Profile screen")
                navController.navigate(Screen.Profile.route)
                viewModel.resetNavigateToProfile()
                preferenceHelper.setProfileChecked(true)
            }
        } else {
            logger.d("Profile check already completed, not navigating.")
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
    // created on the first NDC scan inside InventoryScanViewModel. Routes to the
    // new Batch Stock Count panel (Screen.InventoryScan), NOT the legacy DispenseFlow.
    LaunchedEffect(uiState.pendingStockCountBucketId) {
        uiState.pendingStockCountBucketId?.let { bucketId ->
            navController.navigate(Screen.InventoryScan.createRoute(bucketId = bucketId))
            viewModel.clearPendingStockCountBucketId()
        }
    }

    // Inventory Quick Action goes straight to bucket-select: clicking Inventory always
    // creates a new batch. (Resume-last is reachable from elsewhere if needed.)
    var showBucketSelectDialog by remember { mutableStateOf(false) }

    // When HL7 is disabled from the portal, tapping a partial dispense row in
    // Today's Queue can't resume the PMS-initiated transaction — block it with
    // an informational dialog instead of navigating into the flow.
    var showEnableHl7Dialog by remember { mutableStateOf(false) }

    // ── Build the params bag shared by every variant ──
    // Lambdas are remembered so [DashboardVariantParams] is referentially stable across
    // recompositions — variants and their LazyColumn rows can skip re-render when only
    // `uiState` / `isPmsConnected` change.
    val onKpiFilterTapped = remember(viewModel) { viewModel::onKpiFilterTapped }
    val onDisabledKpiFilterTapped = remember(context) {
        { _: KpiFilter -> showToast(context, R.string.standalone_kpi_disabled_message) }
    }
    val onTabSelected = remember(viewModel) { viewModel::onTabSelected }
    val onDispenseQuickAction = remember(viewModel, navController) {
        {
            viewModel.saveTxnId()
            navController.navigate(
                Screen.DispenseFlow.createRoute(
                    scanType = CountType.FIXED.toString(),
                )
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
        }
    }
    val onRecentBatchClick = remember(navController) {
        { batchId: Long ->
            navController.navigate(Screen.BatchHistoryDetail.createRoute(batchId))
        }
    }
    val onQueueDispenseClick = remember(viewModel, navController) {
        { txnId: Long ->
            if (!viewModel.isHl7Enabled()) {
                showEnableHl7Dialog = true
            } else {
                viewModel.selectCurrentTransaction(txnId)
                navController.navigate(
                    Screen.DispenseFlow.createRoute(
                        scanType = CountType.FIXED.toString(),
                        fromResume = true,
                        fromQueue = true,
                    )
                )
            }
        }
    }
    val onQueueInventoryClick = remember(navController) {
        { batchId: Long ->
            navController.navigate(Screen.InventoryScan.createRoute(batchId))
        }
    }

    val params = DashboardVariantParams(
        uiState = uiState,
        isPmsConnected = connected,
        isHl7Enabled = viewModel.isHl7Enabled(),
        navController = navController,
        onKpiFilterTapped = onKpiFilterTapped,
        disabledKpiFilters = uiState.disabledKpiFilters,
        onDisabledKpiFilterTapped = onDisabledKpiFilterTapped,
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
        // Default the selection to "Normal" (case-insensitive); fall back to the
        // first bucket if there's no Normal. This is the expected default rather
        // than leaving it unselected or landing on an arbitrary bucket (340B).
        val defaultBucketIndex = bucketList
            .indexOfFirst { it.equals("Normal", ignoreCase = true) }
            .let { if (it >= 0) it else if (bucketList.isNotEmpty()) 0 else null }

        CommonSingleSelectDialog(
            title = stringResource(R.string.select_bucket),
            options = bucketList,
            selectedIndex = defaultBucketIndex,
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
    val isTablet = UserInterfaceUtils.isTablet()
    val isLandscape = UserInterfaceUtils.isLandscape()

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

    if (showEnableHl7Dialog) {
        CommonDialog(
            message = stringResource(R.string.please_enable_hl7_message),
            confirmText = stringResource(R.string.ok),
            cancelText = "",
            onConfirm = { showEnableHl7Dialog = false },
            onCancel = { showEnableHl7Dialog = false },
            isSingleButton = true,
        )
    }

    if (pmsCertMismatch) {
        CommonDialog(
            message = stringResource(R.string.pms_cert_mismatch_message),
            confirmText = stringResource(R.string.pms_cert_mismatch_confirm),
            cancelText = stringResource(R.string.cancel),
            onConfirm = { viewModel.clearPmsCertPin() },
            onCancel = { viewModel.clearPmsCertPin() }
        )
    }

    GlobalLoadingOverlay(
        isVisible = uiState.isLoadingQueue,
        message = stringResource(R.string.loading_transactions),
    )
}
