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
import androidx.compose.ui.platform.LocalContext
import com.rite.pillcounting.feature.dispenseScan.presentation.analyzer.FrameBarcodeAnalyzer
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
 * Tablet-landscape inventory shell — VM-driven.
 *
 * Camera runs in NDC-only mode: frames are forwarded to a [FrameBarcodeAnalyzer]
 * which calls back to [InventoryScanViewModel.onBarcodeDetected]. The ML
 * pill-counting interpreter is never initialized in this mode (we omit the
 * onPreviewSizeKnown call that would normally trigger it). Tap SCAN PILLS to
 * enable the legacy pill-count panel (still TODO — see §7c of the doc).
 *
 * The persistent panel reads its state from the InventoryScanViewModel; END
 * COUNT shows the existing end-stock-count confirmation dialog.
 */
@Composable
private fun InventoryTabletLandscapeShell(navController: NavController) {
    val cameraVm: PillScanningViewModel = hiltViewModel()
    val inventoryVm: com.rite.pillcounting.feature.pillCountScan.presentation.viewmodel.InventoryScanViewModel =
        hiltViewModel()

    val cameraUiState by cameraVm.uiState.collectAsState()
    val panelState by inventoryVm.uiState.collectAsState()
    val errorMessage by inventoryVm.errorMessage.collectAsState()
    val showEndCountDialog by inventoryVm.showEndCountDialog.collectAsState()
    val batchEnded by inventoryVm.batchEnded.collectAsState()

    val context = LocalContext.current
    val barcodeAnalyzer = remember { FrameBarcodeAnalyzer(context.applicationContext) }

    // Pause/resume the analyzer based on whether the active NDC card is up.
    // We track activeNdc directly (not a VM-side scannerPaused flag) so the
    // resume path is deterministic: as soon as activeNdc becomes null after
    // ADD/CLEAR or auto-commit, the analyzer is freed to detect the next
    // barcode immediately.
    LaunchedEffect(panelState.activeNdc) {
        if (panelState.activeNdc != null) barcodeAnalyzer.pause() else barcodeAnalyzer.resume()
    }

    // Surface VM errors as toasts. The VM emits a localized resource id +
    // optional arg; we resolve here so the VM stays Context-free.
    LaunchedEffect(errorMessage) {
        errorMessage?.let { err ->
            val text = if (err.formatArg != null) {
                context.getString(err.messageResId, err.formatArg)
            } else {
                context.getString(err.messageResId)
            }
            android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
            inventoryVm.clearErrorMessage()
        }
    }

    // After END COUNT confirms, pop back to dashboard.
    LaunchedEffect(batchEnded) {
        if (batchEnded) navController.popBackStack()
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color(0xFFE5E5E5)),
    ) {
        // Left: live CameraX preview, edge-to-edge.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(androidx.compose.ui.graphics.Color(0xFF2A2A2A))
        ) {
            CameraPreviewSection(
                viewModel = cameraVm,
                pills = cameraUiState.detectedPills,
                isCameraPaused = cameraVm.cameraPaused.collectAsState().value,
                imageFrameWidth = cameraUiState.imageFrameWidth,
                imageFrameHeight = cameraUiState.imageFrameHeight,
                onFrame = { imageProxy ->
                    // Forward each frame to the barcode analyzer. We do NOT
                    // call cameraVm.onFrameCaptured here — that path runs the
                    // ML pill detector, which inventory mode keeps disabled.
                    barcodeAnalyzer.analyze(imageProxy) { raw, _ ->
                        inventoryVm.onBarcodeDetected(raw)
                    }
                },
                onFilteredCountChanged = { /* no-op in inventory mode */ },
                modifier = Modifier.fillMaxSize(),
            )

            BackButton(
                navController = navController,
                showBox = false,
                onClick = { navController.popBackStack() },
            )
        }

        // Right: persistent panel driven by the inventory VM.
        // Left edge is rounded (24 dp) so the panel reads as a curved sheet
        // overlaying the camera; right edge stays flush against the screen.
        Box(
            modifier = Modifier
                .width(500.dp)
                .fillMaxHeight()
                .clip(
                    androidx.compose.foundation.shape.RoundedCornerShape(
                        topStart = 24.dp,
                        bottomStart = 24.dp,
                        topEnd = 0.dp,
                        bottomEnd = 0.dp,
                    )
                )
                .background(androidx.compose.ui.graphics.Color.White)
        ) {
            com.rite.pillcounting.feature.pillCountScan.presentation.variant.BatchStockCountTabletLandscape(
                state = panelState,
                onScanPills = {
                    // §7c — in-place mode toggle is intentionally deferred.
                    // Implementation plan documented in BATCH_STOCK_COUNT_REVAMP.md;
                    // requires:
                    //   1. mode flag (NDC_ONLY / PILL_COUNT)
                    //   2. lazy cameraVm.initializeInterpreter(viewWidth, viewHeight)
                    //   3. swap onFrame target: barcodeAnalyzer.analyze ↔ cameraVm.onFrameCaptured
                    //   4. swap right panel: BatchStockCountTabletLandscape ↔ InformationPanelSection
                    //   5. intercept InformationPanelSection's DONE so it returns to NDC_ONLY mode
                    //      instead of popping the screen.
                    // No-op until this lands so we don't half-implement a state machine
                    // that could corrupt the camera lifecycle.
                },
                onIncrement = inventoryVm::increment,
                onDecrement = inventoryVm::decrement,
                onClear = inventoryVm::onClear,
                onAdd = inventoryVm::onAdd,
                onEndCount = inventoryVm::requestEndCount,
            )
        }
    }

    if (showEndCountDialog) {
        CommonDialog(
            message = stringResource(R.string.are_you_sure_you_want_to_end_this_count),
            title = stringResource(R.string.confirmation),
            confirmText = stringResource(R.string.yes),
            cancelText = stringResource(R.string.no),
            onConfirm = inventoryVm::confirmEndCount,
            onCancel = inventoryVm::dismissEndCount,
        )
    }
}
