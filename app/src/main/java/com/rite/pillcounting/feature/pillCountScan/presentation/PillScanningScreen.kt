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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.rite.pillcounting.feature.dispenseFlow.presentation.analyzer.FrameBarcodeAnalyzer
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
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.ActionButtonPrimary
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.BackButton
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.CommonDialog
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.showToast
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.feature.dispenseFlow.domain.data.NavigationEvent
import com.rite.pillcounting.feature.dispenseFlow.domain.data.PillScanningEvent
import com.rite.pillcounting.feature.dispenseFlow.presentation.compose.AddNoteDialog
import com.rite.pillcounting.feature.dispenseFlow.presentation.compose.CameraPreviewSection
import android.content.res.Configuration
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalConfiguration
import com.rite.pillcounting.feature.dispenseFlow.presentation.compose.HistoryModeLandscape
import com.rite.pillcounting.feature.dispenseFlow.presentation.compose.HistoryModePortrait
import com.rite.pillcounting.feature.dispenseFlow.presentation.compose.InformationPanelSection
import com.rite.pillcounting.feature.dispenseFlow.presentation.compose.StepTitleWithSpeech
import com.rite.pillcounting.feature.dispenseFlow.presentation.compose.TargetPillsCountDialog
import com.rite.pillcounting.feature.dispenseFlow.presentation.viewmodel.PillScanningViewModel
import com.rite.pillcounting.feature.pillCountScan.presentation.shell.InventoryPhoneLandscapeShell
import com.rite.pillcounting.feature.pillCountScan.presentation.shell.InventoryPhonePortraitShell
import com.rite.pillcounting.feature.pillCountScan.presentation.shell.InventoryTabletLandscapeShell
import com.rite.pillcounting.feature.pillCountScan.presentation.shell.InventoryTabletPortraitShell
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
    // When non-zero, scopes the session to this batch (Batch Stock Count SCAN
    // PILLS hand-off); falls back to the previousBackStackEntry lookup when 0.
    batchIdArg: Long = 0L,
) {
    val configuration = LocalConfiguration.current
    val isTablet = configuration.smallestScreenWidthDp >= 600
    val isLandscapeNow = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Inventory mode shows the persistent Batch Stock Count panel; ML/tray
    // detection stay disabled until the user taps SCAN PILLS. All four form
    // factors are wired below.
    if (isInventory) {
        when {
            isTablet && isLandscapeNow -> {
                InventoryTabletLandscapeShell(navController = navController)
                return
            }
            isTablet -> {
                InventoryTabletPortraitShell(navController = navController)
                return
            }
            isLandscapeNow -> {
                // Phone landscape: camera left + narrower panel right (reuses the
                // tablet-landscape panel at a phone-sized width).
                InventoryPhoneLandscapeShell(navController = navController)
                return
            }
            else -> {
                // Phone portrait: draggable bottom sheet over the camera.
                InventoryPhonePortraitShell(navController = navController)
                return
            }
        }
    }

    val context = navController.context
    val uiState by viewModel.uiState.collectAsState()
    val logger = remember { AppLogger("PillScanningScreen") }
    val batchId = if (batchIdArg != 0L) {
        batchIdArg
    } else {
        navController.previousBackStackEntry
            ?.arguments?.getLong(Screen.ScanBarcode.ARG_BATCH_ID) ?: 0L
    }
    val isStockCount = batchId != 0L

    // Barcode analyzer for the compulsory NDC-scan step. Frames route here only
    // while currentStep == SCAN; otherwise to the ML pill detector. We own the
    // ImageProxy on this path and must close it (the analyzer never does).
    val mainContext = LocalContext.current
    val ndcScanAnalyzer = remember {
        FrameBarcodeAnalyzer(mainContext.applicationContext, enableFocusChangeDebounce = false)
    }
    DisposableEffect(Unit) {
        onDispose { ndcScanAnalyzer.close() }
    }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var filteredPillCount by remember { mutableIntStateOf(0) }
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

    LaunchedEffect(uiState.showErrorMessage) {

        uiState.showErrorMessage?.let { message ->
            showToast(context = context, message = message, duration = Toast.LENGTH_SHORT)

            viewModel.clearErrorMessage()
        }
    }

    val stepType by viewModel.currentStep.collectAsState()
    val isSoundEnabled = viewModel.isSoundEnabled.collectAsState().value

    // One-shot side effects keyed on the flag, NOT in the composition body:
    // toasting/resetting the VM during composition can trigger a "write during
    // composition" recomposition loop.
    val maxCountReachedText = stringResource(id = R.string.max_count_reached)
    val noTransactionText = stringResource(id = R.string.no_transaction_found)

    LaunchedEffect(uiState.restrictAdd) {
        if (uiState.restrictAdd) {
            showToast(context, maxCountReachedText)
            logger.w("Toast: Max count reached")
            viewModel.resetRestrictAdd()
        }
    }

    LaunchedEffect(uiState.showNoTransaction) {
        if (uiState.showNoTransaction) {
            showToast(context, noTransactionText)
            logger.w("Toast: No transaction found")
            viewModel.resetNoTransaction()
        }
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

    LaunchedEffect(Unit) {
        viewModel.resetGloveDetection()

        // SCAN PILLS hand-off: arm the compulsory NDC-scan start BEFORE getDrugInfo
        // so the screen opens on StepState.SCAN regardless of any staged/active NDC.
        if (batchIdArg != 0L) {
            viewModel.enterStockCountScanMode(batchId)
        }

        viewModel.getDrugInfo()
        viewModel.showTxnInfo(countType)
        viewModel.observeTxnDetailsForTxn(stepType)
        viewModel.navigationEvent.collectLatest { event ->
            // From the SCAN PILLS hand-off (batchIdArg != 0), DONE returns to the
            // inventory list (not BatchScreen/Dashboard); its Room flow refreshes
            // so the just-counted pills appear on the NDC's row.
            if (batchIdArg != 0L) {
                navController.popBackStack()
                return@collectLatest
            }
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
                    // On the compulsory NDC-scan step (SCAN PILLS hand-off), route
                    // frames to the barcode decoder and close the proxy ourselves.
                    // Otherwise hand off to the ML pill detector (which closes it).
                    if (viewModel.isOnNdcScanStep) {
                        try {
                            ndcScanAnalyzer.analyze(imageProxy) { raw, _ ->
                                viewModel.onNdcScannedForStockCount(raw)
                            }
                        } finally {
                            imageProxy.close()
                        }
                    } else {
                        viewModel.onFrameCaptured(imageProxy)
                    }
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
                        when {
                            // Inventory SCAN PILLS hand-off: BACK returns to the
                            // batch stock-count list (no end-count dialog here —
                            // that's a batch-level action owned by the list).
                            // Back without Done = discard this session's staged
                            // loose pills (earlier committed counts are preserved).
                            batchIdArg != 0L -> {
                                viewModel.discardStagedCount()
                                navController.popBackStack()
                            }
                            isStockCount -> viewModel.showEndStockCountDialog()
                            else -> {
                                viewModel.discardStagedCount()
                                navController.navigate(Screen.Dashboard.route) {
                                    popUpTo(0)
                                    launchSingleTop = true
                                }
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
                    // On the compulsory NDC-scan step show the NDC prompt; on the
                    // pill-count step (REGULAR) show "Scan Open Pills".
                    titleResOverride = when {
                        stepType == StepState.SCAN -> R.string.scan_ndc_to_count
                        countType == CountType.REGULAR.toString() -> R.string.scan_open_pills
                        else -> null
                    }
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
