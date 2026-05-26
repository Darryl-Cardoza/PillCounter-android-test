package com.rite.pillcounting.feature.pillCountScan.presentation

import Screen
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.rite.pillcounting.R
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.ActionButtonPrimary
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.BackButton
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.CommonDialog
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.showToast
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.feature.pillCountScan.domain.data.NavigationEvent
import com.rite.pillcounting.feature.pillCountScan.domain.data.PillScanningEvent
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.AddNoteDialog
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.CameraPreviewSection
import android.content.res.Configuration
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalConfiguration
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.HistoryModeLandscape
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.HistoryModePortrait
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.InformationPanelSection
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.StepTitleWithSpeech
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.TargetPillsCountDialog
import com.rite.pillcounting.feature.pillCountScan.presentation.viewmodel.PillScanningViewModel
import com.rite.pillcounting.ui.theme.AppTheme
import com.rite.pillcounting.ui.theme.AppTheme.dimens
import kotlinx.coroutines.flow.collectLatest
import java.util.Locale

@Composable
fun PillScanningScreen(
    navController: NavController,
    countType: String,
    viewModel: PillScanningViewModel = hiltViewModel(),
    isInventory: Boolean = false,
) {
    val configuration = LocalConfiguration.current
    val isTablet = configuration.smallestScreenWidthDp >= 600
    val isLandscapeNow = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Inventory mode lands here directly from Dashboard with the new persistent
    // Batch Stock Count panel. The ML interpreter and tray-detection paths stay
    // disabled until the user taps SCAN PILLS — at which point we'll flip into
    // the legacy pill-counting UI. For now (UI-only first pass) only the tablet
    // landscape variant is wired; other form factors fall through to the legacy
    // flow until their Figmas are delivered.
    if (isInventory && isTablet && isLandscapeNow) {
        InventoryTabletLandscapeShell(navController = navController)
        return
    }

    val context = navController.context
    val uiState by viewModel.uiState.collectAsState()
    val logger = remember { AppLogger("PillScanningScreen") }
    val batchId = navController.previousBackStackEntry
        ?.arguments?.getLong(Screen.ScanBarcode.ARG_BATCH_ID) ?: 0L
    val isStockCount = batchId != 0L

    // Buffer of last 10 detections
    var lastTenDetections by remember { mutableStateOf<List<Int>>(emptyList()) }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var filteredPillCount by remember { mutableStateOf(0) }
    var showHistory by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(showHistory) {
        if (showHistory) {
            viewModel.pauseIdleTimer()
        } else {
            viewModel.resetIdleTimer()
        }
        viewModel.setCameraPaused(showHistory)
    }
    val totalCount = if (uiState.scanType == CountType.REGULAR.toString()) {
        uiState.stockCountSessionTotal
    } else {
        uiState.txnDetailHistory.sumOf { it.count }
    }

    var previewWidth by rememberSaveable { mutableStateOf<Int?>(null) }
    var previewHeight by rememberSaveable { mutableStateOf<Int?>(null) }

    val showConfirmationDialog = uiState.showDialogForControl
    val showCountMismatchDialog = uiState.showCountMismatchDialog
    val isTxnFromHl7 by viewModel.isTxnFromHl7.collectAsState()

    // Whenever detected pills update, push into buffer
    LaunchedEffect(uiState.detectedPills) {
        val currentCount = uiState.detectedPills.size
        lastTenDetections = (lastTenDetections + currentCount).takeLast(10)
    }
    LaunchedEffect(uiState.showErrorMessage) {

        uiState.showErrorMessage?.let { message ->
            showToast(context = context, message = message, duration = Toast.LENGTH_SHORT)

            viewModel.clearErrorMessage()
        }
    }

    val stepType by viewModel.currentStep.collectAsState()
    val isSoundEnabled = viewModel.isSoundEnabled.collectAsState().value

    // === Toasts ===
    if (uiState.restrictAdd) {
        UserInterfaceUtils.showToast(context, stringResource(id = R.string.max_count_reached))
        logger.w("Toast: Max count reached")
        viewModel.resetRestrictAdd()
    }

    if (uiState.showNoTransaction) {
        UserInterfaceUtils.showToast(context, stringResource(id = R.string.no_transaction_found))
        logger.w("Toast: No transaction found")
        viewModel.resetNoTransaction()
    }

    if (uiState.showTargetCountDialog) {
        TargetPillsCountDialog(
            onDismiss = {
                viewModel.setTargetCountDialogShown(false)
                navController.navigate(Screen.Dashboard.route) {
                    popUpTo(Screen.Dashboard.route) { inclusive = true }
                }
            },
            onOkay = { count ->
                viewModel.updateTargetCount(count)
                viewModel.setTargetCountDialogShown(false)
            }
        )
    }

    if (uiState.showNotesDialog) {
        AddNoteDialog(
            onDismiss = { viewModel.setNoteDialogShown(false) },
            onSkip = {
                viewModel.onEvent(PillScanningEvent.NoteSkip)
            },
            onSave = { note ->
                viewModel.onEvent(PillScanningEvent.NoteSaved(note))
            },
            showSkip = !isTxnFromHl7
        )
    }

    // --- Confirm Dialog ---
    if (uiState.showConfirmDialog) {
        val warningText = if (
            countType == CountType.FIXED.toString() &&
            uiState.txnDetailHistory.sumOf { it.count } < uiState.targetCount
        ) {
            stringResource(R.string.confirm_done_desc_fixed)
        } else {
            stringResource(R.string.confirm_done_desc_regular)
        }
        CommonDialog(
            message = warningText,
            title = stringResource(R.string.confirm_done),
            confirmText = stringResource(R.string.ok),
            cancelText = stringResource(R.string.cancel),
            onConfirm = { viewModel.onEvent(PillScanningEvent.ConfirmDone) },
            onCancel = { viewModel.onEvent(PillScanningEvent.CancelDone) }
        )
    }

    if (showConfirmationDialog) {
        CommonDialog(
            message = stringResource(R.string.are_you_sure_you_want_to_complete_this_step),
            title = stringResource(R.string.confirm_steps_completion),
            confirmText = stringResource(R.string.ok),
            cancelText = stringResource(R.string.cancel),
            onConfirm = {
                viewModel.moveNextStep()
            },
            onCancel = { viewModel.handleDismissDialog() }
        )
    }

    if (showCountMismatchDialog) {
        CommonDialog(
            message = stringResource(R.string.the_counted_quantity_does_not_match_the_target_count),
            title = stringResource(R.string.count_mismatch),
            confirmText = stringResource(R.string.yes),
            cancelText = stringResource(R.string.no),
            onConfirm = {
                viewModel.moveNextStep()
            },
            onCancel = {
                viewModel.resetIdleOverlay()
                viewModel.handleDismissDialog()
            }
        )
    }

    if (uiState.showEndStockCountDialog) {
        CommonDialog(
            message = stringResource(R.string.are_you_sure_you_want_to_end_this_count),
            title = stringResource(R.string.confirmation),
            confirmText = stringResource(R.string.yes),
            cancelText = stringResource(R.string.no),
            onConfirm = {
                viewModel.moveNextStep()
            },
            onCancel = {
                viewModel.resetIdleOverlay()
                viewModel.handleDismissDialog()
            }
        )
    }

    // === Init & Navigation ===
    LaunchedEffect(Unit) {
        // Reset glove detection state when screen loads
        viewModel.resetGloveDetection()

        viewModel.getDrugInfo()
        viewModel.showTxnInfo(countType)
        viewModel.observeTxnDetailsForTxn(stepType)
        viewModel.navigationEvent.collectLatest { event ->
            when (event) {
                is NavigationEvent.NavigateToDashboard -> {
                    navController.navigate(Screen.Dashboard.route)
                }

                is NavigationEvent.NavigateToBatch -> {
                    navController.navigate(Screen.Batch.createRoute(event.batchId))
                }
            }
        }
    }

    LaunchedEffect(countType) {
        viewModel.setScanType(countType)
    }

    // --- Layout ---
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.extendedColors.secondaryBackground)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent()
                        viewModel.resetIdleTimer()
                    }
                }
            }
    ) {
        // Camera + Info — not rendered while history is visible so CameraX cannot
        // rebind on rotation-triggered lifecycle restarts and flash the preview.
        if (!showHistory) {
            CameraPreviewSection(
                viewModel = viewModel,
                pills = uiState.detectedPills,
                isCameraPaused = viewModel.cameraPaused.collectAsState().value,
                onFrame = { imageProxy ->
                    viewModel.onFrameCaptured(imageProxy)
                },
                onFilteredCountChanged = { count -> filteredPillCount = count },
                modifier = Modifier.fillMaxSize(),

                imageFrameWidth = uiState.imageFrameWidth,
                imageFrameHeight = uiState.imageFrameHeight,

                onPreviewSizeKnown = { w, h ->
                    if (previewWidth == null || previewHeight == null) {
                        previewWidth = w
                        previewHeight = h

                        viewModel.initializeInterpreter(
                            retryCount = 2,
                            viewWidth = w,
                            viewHeight = h
                        )
                    }
                }
            )

            Box(
                modifier = if (isLandscape) {
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .fillMaxWidth(0.3f)
                } else {
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(0.25f)
                }
            ) {
                InformationPanelSection(
                    uiState = uiState,
                    viewModel = viewModel,
                    onEvent = viewModel::onEvent,
                    filteredPillCount = filteredPillCount,
                    onShowHistory = { showHistory = true }
                )
            }
        }

        if (!uiState.showIdleOverlay) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {

                BackButton(
                    navController = navController,
                    showBox = false,
                    onClick = {
                        if (isStockCount) {
                            viewModel.showEndStockCountDialog()
                        } else {
                            navController.navigate(Screen.Dashboard.route) {
                                popUpTo(0)
                                launchSingleTop = true
                            }
                        }
                    }
                )
                if (isLandscape) {
                    Spacer(modifier = Modifier.weight(0.3f))
                } else {
                    Spacer(modifier = Modifier.weight(0.6f))
                }

                StepTitleWithSpeech(
                    stepType = stepType,
                    isSoundOverride = isSoundEnabled,
                    titleResOverride = if (countType == CountType.REGULAR.toString()) R.string.scan_open_pills else null
                )

                Spacer(modifier = Modifier.weight(1f))
            }
        }
        if (showHistory) {
            if (isLandscape) {
                HistoryModeLandscape(
                    navController = navController,
                    scanType = uiState.scanType,
                    targetCount = uiState.targetCount,
                    totalCount = totalCount,
                    txnHistory = uiState.txnDetailHistory,
                    onDeleteTxn = { id ->
                        viewModel.onEvent(
                            PillScanningEvent.TransactionDetailDeleted(
                                id
                            )
                        )
                    },
                    viewModel = viewModel,
                    drugName = uiState.drugName,
                    onBack = { showHistory = false }
                )
            } else {
                HistoryModePortrait(
                    navController = navController,
                    scanType = uiState.scanType,
                    targetCount = uiState.targetCount,
                    totalCount = totalCount,
                    txnHistory = uiState.txnDetailHistory,
                    onDeleteTxn = { id ->
                        viewModel.onEvent(
                            PillScanningEvent.TransactionDetailDeleted(
                                id
                            )
                        )
                    },
                    viewModel = viewModel,
                    drugName = uiState.drugName,
                    onBack = { showHistory = false }
                )
            }
        }

        if (uiState.showIdleOverlay && !showHistory) {
            logger.i("Overlay visible")
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppTheme.extendedColors.secondaryBackground.copy(alpha = 0.5f))
                    .pointerInput(Unit) {},
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Text(
                        text = stringResource(R.string.counting_paused).uppercase(Locale.ROOT),
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight.Normal,
                        color = AppTheme.extendedColors.textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    ActionButtonPrimary(
                        text = stringResource(R.string.resume).uppercase(Locale.ROOT),
                        onClick = { viewModel.resetIdleOverlay() },
                        modifier = Modifier.width(dimens.dialogButtonWidth),
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
    }
}

/**
 * Tablet-landscape inventory shell — UI-only first pass.
 *
 * Live CameraX preview on the left (no analyzer wiring yet — frames are dropped
 * via a no-op onFrame, and the ML interpreter is never initialized because we
 * don't call viewModel.initializeInterpreter()). The new persistent Batch Stock
 * Count panel sits on the right. Counter +/- mutate sample state in place.
 * SCAN PILLS / ADD / CLEAR / END COUNT remain stubs until wiring.
 */
@Composable
private fun InventoryTabletLandscapeShell(navController: NavController) {
    val viewModel: PillScanningViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()

    var state by remember {
        mutableStateOf(com.rite.pillcounting.feature.pillCountScan.presentation.compose.BatchStockCountSampleData.activeState)
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.extendedColors.secondaryBackground)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Left: live CameraX preview rendered inside a rounded card with a thin
        // outer margin so the device's screen edge isn't flush with the preview
        // (matches the Figma inset look).
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .background(androidx.compose.ui.graphics.Color(0xFF2A2A2A))
        ) {
            CameraPreviewSection(
                viewModel = viewModel,
                pills = uiState.detectedPills,
                isCameraPaused = viewModel.cameraPaused.collectAsState().value,
                imageFrameWidth = uiState.imageFrameWidth,
                imageFrameHeight = uiState.imageFrameHeight,
                onFrame = { /* no-op — no analyzer wired yet; ML stays off */ },
                onFilteredCountChanged = { /* no-op */ },
                modifier = Modifier.fillMaxSize(),
            )

            // Back arrow overlaid on the top-left of the camera card.
            BackButton(
                navController = navController,
                showBox = false,
                onClick = { navController.popBackStack() },
            )
        }

        // Right: new persistent panel.
        Box(
            modifier = Modifier
                .width(440.dp)
                .fillMaxHeight()
        ) {
            com.rite.pillcounting.feature.pillCountScan.presentation.variant.BatchStockCountTabletLandscape(
                state = state,
                onScanPills = { /* TODO: enable ML + swap to legacy pill-count panel */ },
                onIncrement = {
                    state.activeNdc?.let { a ->
                        state = state.copy(activeNdc = a.copy(bottles = a.bottles + 1))
                    }
                },
                onDecrement = {
                    state.activeNdc?.let { a ->
                        val next = (a.bottles - 1).coerceAtLeast(1)
                        state = state.copy(activeNdc = a.copy(bottles = next))
                    }
                },
                onClear = { state = state.copy(activeNdc = null) },
                onAdd = {
                    state.activeNdc?.let { a ->
                        val newRow = com.rite.pillcounting.feature.pillCountScan.presentation.compose.RecentBatchRow(
                            ndc = a.ndc,
                            drugName = a.drugName,
                            pills = a.totalPills,
                            bottles = a.bottles,
                        )
                        state = state.copy(
                            recentCounts = listOf(newRow) + state.recentCounts,
                            activeNdc = null,
                            totalNdcs = state.totalNdcs + 1,
                            totalPills = state.totalPills + a.totalPills,
                        )
                    }
                },
                onEndCount = { navController.popBackStack() },
            )
        }
    }
}
