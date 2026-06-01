package com.rite.pillcounting.feature.pillCountScan.presentation

import Screen
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.draggable
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
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils
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
import androidx.compose.ui.platform.LocalConfiguration
import com.rite.pillcounting.feature.dispenseFlow.presentation.compose.HistoryModeLandscape
import com.rite.pillcounting.feature.dispenseFlow.presentation.compose.HistoryModePortrait
import com.rite.pillcounting.feature.dispenseFlow.presentation.compose.InformationPanelSection
import com.rite.pillcounting.feature.dispenseFlow.presentation.compose.StepTitleWithSpeech
import com.rite.pillcounting.feature.dispenseFlow.presentation.compose.TargetPillsCountDialog
import com.rite.pillcounting.feature.dispenseFlow.presentation.viewmodel.PillScanningViewModel
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
    // When non-zero, scopes this legacy pill-count session to the given batch
    // (used by the Batch Stock Count SCAN PILLS hand-off). Falls back to the
    // legacy previousBackStackEntry lookup when 0.
    batchIdArg: Long = 0L,
) {
    val configuration = LocalConfiguration.current
    val isTablet = configuration.smallestScreenWidthDp >= 600
    val isLandscapeNow = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Inventory mode lands here directly from Dashboard with the new persistent
    // Batch Stock Count panel. The ML interpreter and tray-detection paths stay
    // disabled until the user taps SCAN PILLS — at which point we'll flip into
    // the legacy pill-counting UI. All four form factors are wired.
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

    // SCAN PILLS hand-off: a barcode analyzer for the compulsory NDC-scan step.
    // Frames are routed here only while currentStep == SCAN; otherwise they go to
    // the ML pill detector. We own the ImageProxy lifecycle on the barcode path
    // and must close it (the analyzer snapshots the frame and never closes it).
    val mainContext = LocalContext.current
    val ndcScanAnalyzer = remember {
        FrameBarcodeAnalyzer(mainContext.applicationContext, enableFocusChangeDebounce = false)
    }

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

        // SCAN PILLS hand-off: arm the compulsory NDC-scan start BEFORE getDrugInfo
        // so the screen opens on StepState.SCAN regardless of any staged/active NDC.
        if (batchIdArg != 0L) {
            viewModel.enterStockCountScanMode(batchId)
        }

        viewModel.getDrugInfo()
        viewModel.showTxnInfo(countType)
        viewModel.observeTxnDetailsForTxn(stepType)
        viewModel.navigationEvent.collectLatest { event ->
            // When launched from the Batch Stock Count SCAN PILLS hand-off
            // (batchIdArg != 0), DONE must return to the inventory list — not the
            // legacy BatchScreen / Dashboard. The list refreshes from its Room
            // flow, so the loose pills just counted appear on the NDC's row.
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

/**
 * Safe back for the inventory shells. `popBackStack()` silently does NOTHING and
 * returns false when this screen is the back-stack root (e.g. reached via a
 * navigation that cleared the stack), which is why BACK sometimes "didn't work".
 * Fall back to navigating to the Dashboard in that case so BACK always leaves.
 */
private fun inventoryBack(navController: NavController) {
    val popped = navController.popBackStack()
    if (!popped) {
        navController.navigate(Screen.Dashboard.route) {
            popUpTo(0)
            launchSingleTop = true
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
    // Tablet uses a wide, full-height stacked-card side panel. Phone landscape is a
    // different layout (slide-out side sheet) — see InventoryPhoneLandscapeShell.
    InventoryLandscapeShell(navController = navController, panelWidth = 500.dp)
}


/**
 * Shared landscape inventory shell — camera on the left, persistent Batch Stock
 * Count panel on the right. [panelWidth] is the only form-factor difference
 * (tablet 500dp, phone 340dp); all VM/camera/analyzer wiring is identical.
 */
@Composable
private fun InventoryLandscapeShell(
    navController: NavController,
    panelWidth: androidx.compose.ui.unit.Dp,
) {
    val cameraVm: PillScanningViewModel = hiltViewModel()
    val inventoryVm: com.rite.pillcounting.feature.pillCountScan.presentation.viewmodel.InventoryScanViewModel =
        hiltViewModel()

    val cameraUiState by cameraVm.uiState.collectAsState()
    val panelState by inventoryVm.uiState.collectAsState()
    val errorMessage by inventoryVm.errorMessage.collectAsState()
    val showEndCountDialog by inventoryVm.showEndCountDialog.collectAsState()
    val batchEnded by inventoryVm.batchEnded.collectAsState()

    val context = LocalContext.current
    val barcodeAnalyzer = remember {
        FrameBarcodeAnalyzer(
            context.applicationContext,
            enableFocusChangeDebounce = true,
        )
    }
    val frameCounter = remember { java.util.concurrent.atomic.AtomicLong(0L) }

    // Camera permission — same gate as the phone-portrait shell. Without it
    // CameraX retries forever and the preview never streams (infinite spinner).
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { hasCameraPermission = it }
    )
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Resume the analyzer whenever the active NDC card clears. We deliberately
    // do NOT pause while activeNdc is non-null — the analyzer self-pauses on
    // each successful hit (see FrameBarcodeAnalyzer), and the VM's
    // onBarcodeDetected handles "different NDC scanned while one is active"
    // by auto-committing the current one. Pausing here would make that
    // branch unreachable: once NDC #1 is on the card, no frames would ever
    // reach the VM to trigger the swap.
    LaunchedEffect(panelState.activeNdc) {
        android.util.Log.d("InventoryScreen", "INV_SCAN LaunchedEffect(activeNdc) → ${panelState.activeNdc?.ndc} hazardous=${panelState.activeNdc?.isHazardous}")
        if (panelState.activeNdc == null) barcodeAnalyzer.resume()
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

    // After END COUNT confirms, leave the screen (pop, or fall back to Dashboard).
    LaunchedEffect(batchEnded) {
        if (batchEnded) inventoryBack(navController)
    }
    // System back gesture: same safe behavior as the on-screen back arrow.
    BackHandler { inventoryBack(navController) }

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
            // Inventory mode bypasses the legacy idle-pause path. The shared
            // PillScanningViewModel auto-pauses after ~30s of idle (intended for
            // the pill-counting flow where it stops the ML pipeline); in
            // inventory we just want continuous barcode scanning until the user
            // taps END COUNT or BACK. Forcing isCameraPaused = false also
            // suppresses the idle overlay so the screen never gets visually
            // stuck on a black/loader state with no recovery affordance.
            // Defensive: reset the idle timer on every recomposition so the
            // legacy VM doesn't flip _cameraPaused = true behind our back.
            LaunchedEffect(Unit) { cameraVm.pauseIdleTimer() }

            if (hasCameraPermission) {
                CameraPreviewSection(
                    viewModel = cameraVm,
                    pills = cameraUiState.detectedPills,
                    isCameraPaused = false,
                    imageFrameWidth = cameraUiState.imageFrameWidth,
                    imageFrameHeight = cameraUiState.imageFrameHeight,
                    showGloveIcon = panelState.activeNdc?.isHazardous == true,
                    onFrame = { imageProxy ->
                        // Forward each frame to the barcode analyzer. We do NOT
                        // call cameraVm.onFrameCaptured here — that path runs the
                        // ML pill detector, which inventory mode keeps disabled.
                        //
                        // Inventory mode owns the ImageProxy lifecycle: the analyzer
                        // synchronously snapshots a Bitmap and never closes the proxy
                        // itself (the dispense flow relies on the pill VM to close
                        // it). Without an explicit close here, CameraX's small frame
                        // pool fills up after the first scan and frameFlow stops
                        // emitting — symptom: only the first NDC is ever detected.
                        val n = frameCounter.incrementAndGet()
                        if (n % 30 == 0L) {
                            android.util.Log.d("InventoryScreen", "INV_SCAN onFrame tick=$n")
                        }
                        try {
                            barcodeAnalyzer.analyze(imageProxy) { raw, _ ->
                                android.util.Log.d("InventoryScreen", "INV_SCAN onBarcode callback raw='$raw' → forwarding to VM")
                                // In focus-change mode the analyzer is not self-paused
                                // on hits — it gates duplicate fires internally based
                                // on empty-frame streak. No resume() needed here.
                                inventoryVm.onBarcodeDetected(raw)
                            }
                        } finally {
                            imageProxy.close()
                        }
                    },
                    onFilteredCountChanged = { /* no-op in inventory mode */ },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.camera_permission_required),
                        color = androidx.compose.ui.graphics.Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }

            BackButton(
                navController = navController,
                showBox = false,
                onClick = { inventoryBack(navController) },
            )
        }

        // Right: persistent panel driven by the inventory VM.
        // Left edge is rounded (24 dp) so the panel reads as a curved sheet
        // overlaying the camera; right edge stays flush against the screen.
        Box(
            modifier = Modifier
                .width(panelWidth)
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
                    // Hand off to the legacy pill-count flow scoped to this batch:
                    // stage the active NDC's txn (PreferenceHelper.saveTxnId) then
                    // navigate. The legacy flow counts loose pills into that txn
                    // and pops back here on DONE; the recent-counts list refreshes
                    // from its Room flow.
                    inventoryVm.onScanPillsForActive { batchId ->
                        navController.navigate(Screen.InventoryPillCount.createRoute(batchId))
                    }
                },
                onIncrement = inventoryVm::increment,
                onDecrement = inventoryVm::decrement,
                // Resume the analyzer explicitly on CLEAR / ADD. The
                // LaunchedEffect(activeNdc) path also resumes, but the analyzer
                // self-pauses on every successful barcode hit — if a resume
                // happens before the self-pause lands (due to coroutine ordering
                // between the suspend lookup and the StateFlow update), the
                // analyzer ends up paused with no further resume scheduled.
                // Calling resume() here directly is order-independent.
                onClear = {
                    android.util.Log.d("InventoryScreen", "INV_SCAN onClear tapped")
                    inventoryVm.onClear()
                    barcodeAnalyzer.resume()
                },
                onAdd = {
                    android.util.Log.d("InventoryScreen", "INV_SCAN onAdd tapped active=${panelState.activeNdc?.ndc}")
                    inventoryVm.onAdd()
                    barcodeAnalyzer.resume()
                },
                onEndCount = inventoryVm::requestEndCount,
                onRowTapped = { row ->
                    inventoryVm.onRecentRowTapped(row)
                    barcodeAnalyzer.resume()
                },
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

/**
 * Phone-landscape inventory shell — VM-driven.
 *
 * Horizontal analog of the phone-portrait bottom sheet: camera fills the screen;
 * a right-docked panel shows only the details/summary card when collapsed, and
 * slides OUTWARD (widens leftward) to reveal the RECENT COUNTS card when dragged.
 * Same VM/camera/analyzer/permission wiring as the other shells.
 */
@Composable
private fun InventoryPhoneLandscapeShell(navController: NavController) {
    val cameraVm: PillScanningViewModel = hiltViewModel()
    val inventoryVm: com.rite.pillcounting.feature.pillCountScan.presentation.viewmodel.InventoryScanViewModel =
        hiltViewModel()

    val cameraUiState by cameraVm.uiState.collectAsState()
    val panelState by inventoryVm.uiState.collectAsState()
    val errorMessage by inventoryVm.errorMessage.collectAsState()
    val showEndCountDialog by inventoryVm.showEndCountDialog.collectAsState()
    val batchEnded by inventoryVm.batchEnded.collectAsState()

    val context = LocalContext.current
    val barcodeAnalyzer = remember {
        FrameBarcodeAnalyzer(context.applicationContext, enableFocusChangeDebounce = true)
    }
    val frameCounter = remember { java.util.concurrent.atomic.AtomicLong(0L) }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { hasCameraPermission = it }
    )
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(panelState.activeNdc) {
        if (panelState.activeNdc == null) barcodeAnalyzer.resume()
    }
    LaunchedEffect(errorMessage) {
        errorMessage?.let { err ->
            val text = if (err.formatArg != null) context.getString(err.messageResId, err.formatArg)
            else context.getString(err.messageResId)
            android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
            inventoryVm.clearErrorMessage()
        }
    }
    LaunchedEffect(batchEnded) {
        if (batchEnded) inventoryBack(navController)
    }
    // System back gesture: same safe behavior as the on-screen back arrow.
    BackHandler { inventoryBack(navController) }

    val canEndCount = panelState.totalNdcs > 0 || panelState.activeNdc != null

    // Collapsed = just the details card; expanded = details + recent-counts card.
    val detailsCardWidth = 340.dp
    val collapsedWidth = detailsCardWidth + 28.dp        // + panel horizontal padding
    val expandedWidth = collapsedWidth + 300.dp          // room for the recent card
    var expanded by remember { mutableStateOf(false) }
    val panelWidth by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (expanded) expandedWidth else collapsedWidth,
        label = "panelWidth",
    )

    LaunchedEffect(Unit) { cameraVm.pauseIdleTimer() }

    Box(modifier = Modifier.fillMaxSize()) {
        // Full-screen camera behind the panel.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color(0xFF2A2A2A))
        ) {
            if (hasCameraPermission) {
                CameraPreviewSection(
                    viewModel = cameraVm,
                    pills = cameraUiState.detectedPills,
                    isCameraPaused = false,
                    imageFrameWidth = cameraUiState.imageFrameWidth,
                    imageFrameHeight = cameraUiState.imageFrameHeight,
                    showGloveIcon = panelState.activeNdc?.isHazardous == true,
                    onFrame = { imageProxy ->
                        val n = frameCounter.incrementAndGet()
                        if (n % 30 == 0L) {
                            android.util.Log.d("InventoryScreen", "INV_SCAN(phone-ls) onFrame tick=$n")
                        }
                        try {
                            barcodeAnalyzer.analyze(imageProxy) { raw, _ ->
                                inventoryVm.onBarcodeDetected(raw)
                            }
                        } finally {
                            imageProxy.close()
                        }
                    },
                    onFilteredCountChanged = { /* no-op in inventory mode */ },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.camera_permission_required),
                        color = androidx.compose.ui.graphics.Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }

            BackButton(navController = navController, showBox = false, onClick = { inventoryBack(navController) })
        }

        // Right-docked panel. A horizontal drag toggles expanded/collapsed:
        // dragging left (negative) expands to reveal recent counts; right collapses.
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(panelWidth)
                .fillMaxHeight()
                .clip(
                    androidx.compose.foundation.shape.RoundedCornerShape(
                        topStart = 24.dp, bottomStart = 24.dp, topEnd = 0.dp, bottomEnd = 0.dp,
                    )
                )
                .background(androidx.compose.ui.graphics.Color(0xFFF2F2F2))
                .draggable(
                    orientation = androidx.compose.foundation.gestures.Orientation.Horizontal,
                    state = androidx.compose.foundation.gestures.rememberDraggableState { delta ->
                        // Drag left (delta < 0) → expand; drag right → collapse.
                        if (delta < -8f) expanded = true
                        else if (delta > 8f) expanded = false
                    },
                )
        ) {
            com.rite.pillcounting.feature.pillCountScan.presentation.variant.BatchStockCountPhoneLandscape(
                state = panelState,
                recentVisible = expanded,
                detailsCardWidth = detailsCardWidth,
                onScanPills = {
                    inventoryVm.onScanPillsForActive { batchId ->
                        navController.navigate(Screen.InventoryPillCount.createRoute(batchId))
                    }
                },
                onIncrement = inventoryVm::increment,
                onDecrement = inventoryVm::decrement,
                onClear = {
                    inventoryVm.onClear()
                    barcodeAnalyzer.resume()
                },
                onAdd = {
                    inventoryVm.onAdd()
                    barcodeAnalyzer.resume()
                },
                onEndCount = inventoryVm::requestEndCount,
                endCountEnabled = canEndCount,
                onRowTapped = { row ->
                    inventoryVm.onRecentRowTapped(row)
                    barcodeAnalyzer.resume()
                },
                modifier = Modifier.fillMaxHeight(),
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

/**
 * Tablet-portrait inventory shell — VM-driven.
 *
 * Identical data flow and camera/analyzer wiring to [InventoryTabletLandscapeShell];
 * only the layout differs (camera on top, panel pinned to the bottom as a
 * fixed-height sheet) per the portrait Figma. All VM callbacks, the barcode
 * analyzer lifecycle, error toasts, and the end-count dialog are the same.
 */
@Composable
private fun InventoryTabletPortraitShell(navController: NavController) {
    val cameraVm: PillScanningViewModel = hiltViewModel()
    val inventoryVm: com.rite.pillcounting.feature.pillCountScan.presentation.viewmodel.InventoryScanViewModel =
        hiltViewModel()

    val cameraUiState by cameraVm.uiState.collectAsState()
    val panelState by inventoryVm.uiState.collectAsState()
    val errorMessage by inventoryVm.errorMessage.collectAsState()
    val showEndCountDialog by inventoryVm.showEndCountDialog.collectAsState()
    val batchEnded by inventoryVm.batchEnded.collectAsState()

    val context = LocalContext.current
    val barcodeAnalyzer = remember {
        FrameBarcodeAnalyzer(
            context.applicationContext,
            enableFocusChangeDebounce = true,
        )
    }
    val frameCounter = remember { java.util.concurrent.atomic.AtomicLong(0L) }

    // Camera permission — same gate as the other inventory shells. Without it
    // CameraX retries forever and the preview never streams (infinite spinner).
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { hasCameraPermission = it }
    )
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // See landscape shell for the rationale: only resume on activeNdc clearing;
    // never pause while a card is active, or the auto-swap branch becomes
    // unreachable.
    LaunchedEffect(panelState.activeNdc) {
        if (panelState.activeNdc == null) barcodeAnalyzer.resume()
    }

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

    LaunchedEffect(batchEnded) {
        if (batchEnded) inventoryBack(navController)
    }
    // System back gesture: same safe behavior as the on-screen back arrow.
    BackHandler { inventoryBack(navController) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color(0xFFE5E5E5)),
    ) {
        // Top: live CameraX preview takes the remaining space above the sheet.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(androidx.compose.ui.graphics.Color(0xFF2A2A2A))
        ) {
            // Inventory mode bypasses the legacy idle-pause path (see landscape
            // shell). Reset the idle timer so the legacy VM never flips the
            // camera to paused behind our back.
            LaunchedEffect(Unit) { cameraVm.pauseIdleTimer() }

            if (hasCameraPermission) {
                CameraPreviewSection(
                    viewModel = cameraVm,
                    pills = cameraUiState.detectedPills,
                    isCameraPaused = false,
                    imageFrameWidth = cameraUiState.imageFrameWidth,
                    imageFrameHeight = cameraUiState.imageFrameHeight,
                    showGloveIcon = panelState.activeNdc?.isHazardous == true,
                    onFrame = { imageProxy ->
                        // Forward each frame to the barcode analyzer. We own the
                        // ImageProxy lifecycle here and must close it (see landscape
                        // shell for the full explanation) or CameraX's frame pool
                        // fills up after the first scan.
                        val n = frameCounter.incrementAndGet()
                        if (n % 30 == 0L) {
                            android.util.Log.d("InventoryScreen", "INV_SCAN(portrait) onFrame tick=$n")
                        }
                        try {
                            barcodeAnalyzer.analyze(imageProxy) { raw, _ ->
                                inventoryVm.onBarcodeDetected(raw)
                            }
                        } finally {
                            imageProxy.close()
                        }
                    },
                    onFilteredCountChanged = { /* no-op in inventory mode */ },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.camera_permission_required),
                        color = androidx.compose.ui.graphics.Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }

            BackButton(
                navController = navController,
                showBox = false,
                onClick = { inventoryBack(navController) },
            )
        }

        // Bottom: fixed-height batch-stock-count sheet. Trimmed to ~38% so the
        // sheet hugs its content (the counter group no longer floats in a tall
        // card) and the camera preview gets more room — matches Figma proportions.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.38f)
        ) {
            com.rite.pillcounting.feature.pillCountScan.presentation.variant.BatchStockCountTabletPortrait(
                state = panelState,
                onScanPills = {
                    // Hand off to the legacy pill-count flow scoped to this batch
                    // (see landscape shell for the full rationale).
                    inventoryVm.onScanPillsForActive { batchId ->
                        navController.navigate(Screen.InventoryPillCount.createRoute(batchId))
                    }
                },
                onIncrement = inventoryVm::increment,
                onDecrement = inventoryVm::decrement,
                onClear = {
                    inventoryVm.onClear()
                    barcodeAnalyzer.resume()
                },
                onAdd = {
                    inventoryVm.onAdd()
                    barcodeAnalyzer.resume()
                },
                onEndCount = inventoryVm::requestEndCount,
                onRowTapped = { row ->
                    inventoryVm.onRecentRowTapped(row)
                    barcodeAnalyzer.resume()
                },
                modifier = Modifier.fillMaxHeight(),
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

/**
 * Phone-portrait inventory shell — VM-driven.
 *
 * Identical data flow and camera/analyzer wiring to the tablet shells; the
 * layout is a persistent draggable [BottomSheetScaffold] over the camera:
 *  - Collapsed (peek): header + SCANNED NDC DETAILS card/counter (or the empty
 *    placeholder + SCANNED SUMMARY).
 *  - Expanded (pull up): the RECENT COUNTS list is revealed below the card.
 *
 * The sheet is non-dismissible (skipHiddenState) so it always shows at least the
 * peek height — there's no "hidden" state for a persistent scan panel.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun InventoryPhonePortraitShell(navController: NavController) {
    val cameraVm: PillScanningViewModel = hiltViewModel()
    val inventoryVm: com.rite.pillcounting.feature.pillCountScan.presentation.viewmodel.InventoryScanViewModel =
        hiltViewModel()

    val cameraUiState by cameraVm.uiState.collectAsState()
    val panelState by inventoryVm.uiState.collectAsState()
    val errorMessage by inventoryVm.errorMessage.collectAsState()
    val showEndCountDialog by inventoryVm.showEndCountDialog.collectAsState()
    val batchEnded by inventoryVm.batchEnded.collectAsState()

    val context = LocalContext.current
    val barcodeAnalyzer = remember {
        FrameBarcodeAnalyzer(
            context.applicationContext,
            enableFocusChangeDebounce = true,
        )
    }
    val frameCounter = remember { java.util.concurrent.atomic.AtomicLong(0L) }

    // Camera permission. Without it CameraX silently retries forever and the
    // preview never streams (infinite spinner). Request it on entry and only
    // render the camera once granted.
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { hasCameraPermission = it }
    )
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // See landscape shell for the rationale: only resume on activeNdc clearing.
    LaunchedEffect(panelState.activeNdc) {
        if (panelState.activeNdc == null) barcodeAnalyzer.resume()
    }

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

    LaunchedEffect(batchEnded) {
        if (batchEnded) inventoryBack(navController)
    }
    // System back gesture: same safe behavior as the on-screen back arrow.
    BackHandler { inventoryBack(navController) }

    val scaffoldState = androidx.compose.material3.rememberBottomSheetScaffoldState(
        bottomSheetState = androidx.compose.material3.rememberStandardBottomSheetState(
            initialValue = androidx.compose.material3.SheetValue.PartiallyExpanded,
            skipHiddenState = true,
        )
    )

    // Peek height is measured from the panel's always-visible region (header +
    // card) so the collapsed sheet shows the full card/counter without a fixed
    // guess that clipped the counter. Default until first measure.
    val density = androidx.compose.ui.platform.LocalDensity.current
    var peekHeightPx by remember { mutableStateOf(0) }
    // Peek = exactly the measured content height. The drag handle is removed and
    // the panel's own top + bottom padding are now INSIDE the measured region, so
    // no extra allowance is needed — adding any leaks the recent-counts header
    // into the collapsed peek.
    val peekHeight = with(density) { peekHeightPx.toDp() }.coerceAtLeast(320.dp)
    // The Recent Counts list (revealed on expand) gets the screen height minus the
    // peek so the inner LazyColumn stays bounded.
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp
    val listMaxHeight = (screenHeightDp - peekHeight).coerceAtLeast(120.dp)

    // Validation: END COUNT only allowed once at least one NDC has been scanned
    // (committed row or an active card). Nothing scanned → button disabled.
    val canEndCount = panelState.totalNdcs > 0 || panelState.activeNdc != null

    LaunchedEffect(Unit) { cameraVm.pauseIdleTimer() }

    // Camera lives in a full-screen Box at the BASE of the stack; the sheet
    // scaffold is layered ON TOP with a transparent body. Putting the CameraX
    // PreviewView inside the scaffold's body produced a preview that never
    // streamed (the surface wasn't laid out) — keeping it as a plain full-screen
    // child, exactly like the tablet shells, fixes that.
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color(0xFF2A2A2A))
        ) {
            // Only mount the camera once permission is granted — otherwise CameraX
            // retries forever and the preview never streams.
            if (hasCameraPermission) {
                CameraPreviewSection(
                    viewModel = cameraVm,
                    pills = cameraUiState.detectedPills,
                    isCameraPaused = false,
                    imageFrameWidth = cameraUiState.imageFrameWidth,
                    imageFrameHeight = cameraUiState.imageFrameHeight,
                    showGloveIcon = panelState.activeNdc?.isHazardous == true,
                    onFrame = { imageProxy ->
                        val n = frameCounter.incrementAndGet()
                        if (n % 30 == 0L) {
                            android.util.Log.d("InventoryScreen", "INV_SCAN(phone) onFrame tick=$n")
                        }
                        try {
                            barcodeAnalyzer.analyze(imageProxy) { raw, _ ->
                                inventoryVm.onBarcodeDetected(raw)
                            }
                        } finally {
                            imageProxy.close()
                        }
                    },
                    onFilteredCountChanged = { /* no-op in inventory mode */ },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // Permission not yet granted — prompt the user to allow it.
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.camera_permission_required),
                        color = androidx.compose.ui.graphics.Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        }

        androidx.compose.material3.BottomSheetScaffold(
            scaffoldState = scaffoldState,
            // Peek = measured header+card height, so the collapsed sheet always
            // shows the full counter/buttons. Dragging up reveals RECENT COUNTS.
            sheetPeekHeight = peekHeight,
            sheetContainerColor = androidx.compose.ui.graphics.Color(0xFFF2F2F2),
            // No drag handle — removes the grey pill line at the top and lets the
            // "Batch Stock Count" header sit higher. The sheet still drags.
            sheetDragHandle = null,
            // Transparent body so the camera Box behind shows through; the sheet
            // is the only visible scaffold surface.
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            sheetContent = {
                com.rite.pillcounting.feature.pillCountScan.presentation.variant.BatchStockCountPhonePortrait(
                    state = panelState,
                    onScanPills = {
                        inventoryVm.onScanPillsForActive { batchId ->
                            navController.navigate(Screen.InventoryPillCount.createRoute(batchId))
                        }
                    },
                    onIncrement = inventoryVm::increment,
                    onDecrement = inventoryVm::decrement,
                    onClear = {
                        inventoryVm.onClear()
                        barcodeAnalyzer.resume()
                    },
                    onAdd = {
                        inventoryVm.onAdd()
                        barcodeAnalyzer.resume()
                    },
                    onEndCount = inventoryVm::requestEndCount,
                    endCountEnabled = canEndCount,
                    onRowTapped = { row ->
                        inventoryVm.onRecentRowTapped(row)
                        barcodeAnalyzer.resume()
                    },
                    onPeekHeightChanged = { peekHeightPx = it },
                    listMaxHeight = listMaxHeight,
                )
            },
        ) { _ ->
            // Body intentionally empty — the camera is the full-screen Box behind
            // this scaffold. Box() keeps the body transparent and zero-content.
            Box(modifier = Modifier.fillMaxSize())
        }

        // Back arrow LAST in the outer Box so it draws ON TOP of the (full-size,
        // transparent) BottomSheetScaffold and actually receives taps — when it
        // was inside the camera Box underneath the scaffold, the scaffold's body
        // intercepted the touch and back appeared to do nothing.
        BackButton(
            navController = navController,
            showBox = false,
            onClick = { inventoryBack(navController) },
        )
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
