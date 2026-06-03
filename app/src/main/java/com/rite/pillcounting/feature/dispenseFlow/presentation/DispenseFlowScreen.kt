package com.rite.pillcounting.feature.dispenseFlow.presentation

import Screen
import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.ui.zIndex
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.rite.pillcounting.R
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.utils.common.BarcodeDecoder
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.ActionButtonPrimary
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.BackButton
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.CommonDialog
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.showToast
import com.rite.pillcounting.core.utils.compose.DialogField
import com.rite.pillcounting.core.utils.compose.VerifyNdcDetailsInlinePanel
import com.rite.pillcounting.core.utils.compose.VerifyNdcDetailsSheet
import com.rite.pillcounting.core.utils.compose.VerifyRxDetailsInlinePanel
import com.rite.pillcounting.core.utils.compose.VerifyRxDetailsSheet
import com.rite.pillcounting.core.utils.compose.VerifyStockBottleInlinePanel
import com.rite.pillcounting.core.utils.compose.VerifyStockBottleSheet
import com.rite.pillcounting.feature.dispenseFlow.presentation.analyzer.FrameBarcodeAnalyzer
import com.rite.pillcounting.feature.dispenseFlow.presentation.viewmodel.DispenseFlowViewModel
import com.rite.pillcounting.feature.dispenseFlow.domain.model.DispenseStage
import com.rite.pillcounting.feature.dispenseFlow.domain.data.NavigationEvent as PillNavigationEvent
import com.rite.pillcounting.feature.dispenseFlow.domain.data.PillScanningEvent
import com.rite.pillcounting.feature.dispenseFlow.presentation.compose.AddNoteDialog
import com.rite.pillcounting.feature.dispenseFlow.presentation.compose.CameraPreviewSection
import com.rite.pillcounting.feature.dispenseFlow.presentation.compose.HistoryModeLandscape
import com.rite.pillcounting.feature.dispenseFlow.presentation.compose.HistoryModePortrait
import com.rite.pillcounting.feature.dispenseFlow.presentation.compose.InformationPanelSection
import com.rite.pillcounting.feature.dispenseFlow.presentation.compose.StepTitleWithSpeech
import com.rite.pillcounting.feature.dispenseFlow.presentation.compose.TargetPillsCountDialog
import com.rite.pillcounting.feature.dispenseFlow.presentation.viewmodel.PillScanningViewModel
import com.rite.pillcounting.ui.theme.AppTheme
import com.rite.pillcounting.ui.theme.AppTheme.dimens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import java.util.Locale

/**
 * Merged dispense flow screen.
 *
 * Three stages live on one screen:
 *   1. PRE_RX   : Both the barcode analyzer and the pill detector run. The barcode
 *                 analyzer looks for the RX label. Pill detection is used only to
 *                 fire the voice prompt when pills are framed ("Scan RX to add the
 *                 pill count"); a live count circle is shown but the full pill panel
 *                 (Add / Done) is hidden.
 *   2. PRE_NDC  : Same dual-analyzer approach, now looking for the container barcode.
 *                 Voice prompt changes to "Scan the container QR code". The count
 *                 circle remains visible; the full pill panel stays hidden.
 *   3. COUNTING : Barcode analyzer stops. Full pill panel (total / target / Add / Done)
 *                 appears immediately. [PillScanningViewModel] workflow takes over.
 *                 Glove model is loaded here if the drug is hazardous.
 *
 * Both analyzers run simultaneously in the pre-stages. The pill detector is paused
 * (not stopped) whenever a verification sheet, error dialog, or loading indicator
 * is visible — this avoids stale detections and saves CPU/GPU while the user is
 * looking at modal UI.
 *
 * Voice guidance is provided via [DispenseVoicePrompt]. The step-title speech
 * component (StepTitleWithSpeech) is only rendered during COUNTING.
 */

// Grace period before hiding the count circle after detections drop to 0,
// so momentary empty frames don't flicker the circle off and on.
private const val COUNT_CIRCLE_HIDE_GRACE_MS = 700L

@Composable
fun DispenseFlowScreen(
    navController: NavController,
    countType: String,
    fromHl7: Boolean = false,
    fromResume: Boolean = false,
    batchId: Long = 0L,
    dispenseVm: DispenseFlowViewModel = hiltViewModel(),
    pillVm: PillScanningViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val dispenseState by dispenseVm.uiState.collectAsState()
    val pillState by pillVm.uiState.collectAsState()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isSoundEnabled by pillVm.isSoundEnabled.collectAsState()

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

    LaunchedEffect(countType) {
        dispenseVm.setCountType(countType)
        pillVm.setScanType(countType)
    }

    LaunchedEffect(batchId) {
        dispenseVm.setBatchId(batchId)
    }

    // One-time init for the pill counting workflow side: reset glove state and
    // start listening for the pillVm's own navigation events (Done → Dashboard /
    // Batch). Without this collect, hitting "All Done" in the pill panel would
    // fire the confirmation dialog but the user would never actually leave the
    // screen on confirm.
    LaunchedEffect(Unit) {
        pillVm.resetGloveDetection()
        pillVm.navigationEvent.collectLatest { event ->
            when (event) {
                is PillNavigationEvent.NavigateToDashboard -> {
                    navController.navigate(Screen.Dashboard.route)
                }
                is PillNavigationEvent.NavigateToBatch -> {
                    navController.navigate(Screen.Batch.createRoute(event.batchId))
                }
            }
        }
    }

    // PMS/HL7 entry: hydrate from the pre-existing transaction and skip RX.
    // Only runs once — the VM internally early-outs if no txn is found, so we
    // safely fall back to the manual PRE_RX flow.
    LaunchedEffect(fromHl7) {
        if (fromHl7) dispenseVm.initializeFromHl7Txn()
    }

    // Resume entry: partial transaction already exists, NDC not yet verified.
    // Skip RX scan and go straight to PRE_NDC so the user only scans the container.
    LaunchedEffect(fromResume) {
        if (fromResume) dispenseVm.initializeFromResumedTxn()
    }

    // History toggle. When true, the camera + pill panel are hidden and the
    // history list takes over the screen — same toggle the legacy
    // PillScanningScreen used. Tapping the Total Count on the pill panel sets
    // this to true.
    var showHistory by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(showHistory) {
        if (showHistory) {
            pillVm.pauseIdleTimer()
        } else {
            pillVm.resetIdleTimer()
        }
        pillVm.setCameraPaused(showHistory)
    }

    val pillStepType by pillVm.currentStep.collectAsState()
    val isTxnFromHl7 by pillVm.isTxnFromHl7.collectAsState()
    val glovesDetected by pillVm.glovesDetected.collectAsState()

    // === Pill-VM toasts ===
    if (pillState.restrictAdd) {
        UserInterfaceUtils.showToast(context, stringResource(id = R.string.max_count_reached))
        pillVm.resetRestrictAdd()
    }
    if (pillState.showNoTransaction) {
        UserInterfaceUtils.showToast(context, stringResource(id = R.string.no_transaction_found))
        pillVm.resetNoTransaction()
    }
    LaunchedEffect(pillState.showErrorMessage) {
        pillState.showErrorMessage?.let { message ->
            showToast(context = context, message = message, duration = Toast.LENGTH_SHORT)
            pillVm.clearErrorMessage()
        }
    }

    // === Pill-VM dialogs ===
    if (pillState.showTargetCountDialog) {
        TargetPillsCountDialog(
            onDismiss = {
                pillVm.setTargetCountDialogShown(false)
                navController.navigate(Screen.Dashboard.route) {
                    popUpTo(Screen.Dashboard.route) { inclusive = true }
                }
            },
            onOkay = { count ->
                pillVm.updateTargetCount(count)
                pillVm.setTargetCountDialogShown(false)
            }
        )
    }

    if (pillState.showNotesDialog) {
        AddNoteDialog(
            onDismiss = { pillVm.setNoteDialogShown(false) },
            onSkip = { pillVm.onEvent(PillScanningEvent.NoteSkip) },
            onSave = { note -> pillVm.onEvent(PillScanningEvent.NoteSaved(note)) },
            showSkip = !isTxnFromHl7,
        )
    }

    // "Confirm Done" — fires after the user taps All Done and the pill VM
    // decides whether the count is final. For FIXED counts under target, the
    // message text is the "did you mean to stop short?" warning.
    if (pillState.showConfirmDialog) {
        val warningText = if (
            countType == CountType.FIXED.toString() &&
            pillState.txnDetailHistory.sumOf { it.count } < pillState.targetCount
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
            onConfirm = { pillVm.onEvent(PillScanningEvent.ConfirmDone) },
            onCancel = { pillVm.onEvent(PillScanningEvent.CancelDone) },
        )
    }

    // "Confirm Step Completion" — surfaced on All Done at intermediate workflow
    // steps (CONTAINER_INITIATE / TARGET_VERIFICATION etc.). Advances the
    // workflow on confirm.
    if (pillState.showDialogForControl) {
        CommonDialog(
            message = stringResource(R.string.are_you_sure_you_want_to_complete_this_step),
            title = stringResource(R.string.confirm_steps_completion),
            confirmText = stringResource(R.string.ok),
            cancelText = stringResource(R.string.cancel),
            onConfirm = { pillVm.moveNextStep() },
            onCancel = { pillVm.handleDismissDialog() },
        )
    }

    if (pillState.showCountMismatchDialog) {
        CommonDialog(
            message = stringResource(R.string.the_counted_quantity_does_not_match_the_target_count),
            title = stringResource(R.string.count_mismatch),
            confirmText = stringResource(R.string.yes),
            cancelText = stringResource(R.string.no),
            onConfirm = { pillVm.moveNextStep() },
            onCancel = {
                pillVm.resetIdleOverlay()
                pillVm.handleDismissDialog()
            },
        )
    }

    if (pillState.showEndStockCountDialog) {
        CommonDialog(
            message = stringResource(R.string.are_you_sure_you_want_to_end_this_count),
            title = stringResource(R.string.confirmation),
            confirmText = stringResource(R.string.yes),
            cancelText = stringResource(R.string.no),
            onConfirm = { pillVm.moveNextStep() },
            onCancel = {
                pillVm.resetIdleOverlay()
                pillVm.handleDismissDialog()
            },
        )
    }

    // ── Tray color classification popup ─────────────────────────────────────
    // Shown when a tray color is detected during a hazardous transaction and has
    // not been previously classified as hazardous or non-hazardous.
    pillState.pendingTrayColorForClassification?.let { pendingColor ->
        CommonDialog(
            title = stringResource(R.string.tray_classification_title),
            message = stringResource(R.string.tray_classification_message, pendingColor.label),
            confirmText = stringResource(R.string.yes),
            cancelText = stringResource(R.string.no),
            onConfirm = { pillVm.classifyTrayColor(pendingColor, isHazardous = true) },
            onCancel  = { pillVm.classifyTrayColor(pendingColor, isHazardous = false) },
        )
    }

    // ── Camera / detector gating ────────────────────────────────────────────
    // Pill detection runs in all three stages. Pre-RX/pre-NDC we use it only as
    // a signal to fire the "scan RX" / "scan container" voice prompt when pills
    // are framed (the pill panel itself stays hidden until COUNTING). To keep
    // the pre-stages responsive we still pause the detector while any
    // verification sheet or error dialog is up (handled by the second
    // LaunchedEffect below).
    LaunchedEffect(dispenseState.stage) {
        if (dispenseState.stage == DispenseStage.COUNTING) {
            pillVm.resumePillDetection()
            pillVm.getDrugInfo()
            pillVm.showTxnInfo(countType)
            // Enable tray color detection only for hazardous transactions in COUNTING stage.
            pillVm.setHazardousTransaction(dispenseState.isHazardous)
            // Load glove model only now that RX + NDC are confirmed and drug is hazardous.
            if (dispenseState.isHazardous) {
                pillVm.loadGloveModelAndRebuildAnalyzer()
            }
        } else {
            // PRE_RX / PRE_NDC — run the detector so the voice prompt can react
            // to pills-in-frame, but keep CPU/GPU off when an overlay is active.
            pillVm.resumePillDetection()
        }
    }

    // Pause pill detection any time a verification sheet, popup, error dialog,
    // or loading state is on top of the camera. Saves CPU/GPU while user is
    // looking at modal UI; also avoids stale detections flickering if the user
    // looks at pills while the RX sheet is visible.
    LaunchedEffect(
        dispenseState.showRxDetails,
        dispenseState.showNdcDetails,
        dispenseState.showNdcNotFoundDialog,
        dispenseState.showInvalidScanDialog,
        dispenseState.showNdcEquivalenceDialog,
        dispenseState.showRxScannedInStockCountDialog,
        dispenseState.isLoading,
    ) {
        val anyOverlay = dispenseState.showRxDetails ||
                dispenseState.showNdcDetails ||
                dispenseState.showNdcNotFoundDialog ||
                dispenseState.showInvalidScanDialog ||
                dispenseState.showNdcEquivalenceDialog ||
                dispenseState.showRxScannedInStockCountDialog ||
                dispenseState.isLoading
        if (anyOverlay) pillVm.pausePillDetection()
        else pillVm.resumePillDetection()
    }

    val barcodeAnalyzer = remember { FrameBarcodeAnalyzer(context.applicationContext) }
    DisposableEffect(Unit) {
        onDispose { barcodeAnalyzer.pause() }
    }

    // Safety net for process/nav death: if the screen leaves composition with
    // staged-but-uncommitted loose pills (e.g. an unhandled back path), discard
    // them. After a successful Done the buffer is already flushed/cleared, so
    // this is a no-op in that case.
    DisposableEffect(Unit) {
        onDispose { pillVm.discardStagedCount() }
    }

    // Pause/resume the barcode analyzer to match the stage. Outside the
    // pre-stages the barcode scanner sits idle; while ANY verification sheet,
    // error dialog, or loading indicator is on top of the camera we don't want
    // to keep firing the analyzer either — otherwise we burn CPU on reads we'd
    // immediately ignore, AND we risk re-triggering the same barcode the moment
    // the dialog closes.
    LaunchedEffect(
        dispenseState.stage,
        dispenseState.showRxDetails,
        dispenseState.showNdcDetails,
        dispenseState.showNdcNotFoundDialog,
        dispenseState.showInvalidScanDialog,
        dispenseState.showNdcEquivalenceDialog,
        dispenseState.showRxScannedInStockCountDialog,
        dispenseState.isLoading,
    ) {
        val shouldRun = (dispenseState.stage == DispenseStage.PRE_RX ||
                dispenseState.stage == DispenseStage.PRE_NDC) &&
                !dispenseState.showRxDetails &&
                !dispenseState.showNdcDetails &&
                !dispenseState.showNdcNotFoundDialog &&
                !dispenseState.showInvalidScanDialog &&
                !dispenseState.showNdcEquivalenceDialog &&
                !dispenseState.showRxScannedInStockCountDialog &&
                !dispenseState.isLoading
        if (shouldRun) barcodeAnalyzer.resume() else barcodeAnalyzer.pause()
    }

    // Track in-flight pill count from the detector (only meaningful in COUNTING).
    var filteredPillCount by remember { mutableStateOf(0) }

    // ── Voice prompts ───────────────────────────────────────────────────────
    val pillsDetected = pillState.detectedPills.isNotEmpty() || filteredPillCount > 0
    DispenseVoicePrompt(
        stage = dispenseState.stage,
        pillsDetected = pillsDetected,
        isSoundEnabled = isSoundEnabled,
        isAnyOverlayShowing = dispenseState.showRxDetails || dispenseState.showNdcDetails,
    )

    // ── Back handling ───────────────────────────────────────────────────────
    // Device-back priority order:
    //  1. If the history view is open, dismiss it (return to camera + pill panel).
    //  2. Otherwise, exit the dispense flow to the Dashboard.
    // This matches the on-screen back arrows: the history view has its own
    // HeadlineBar back arrow wired to dismiss history; we keep behavior
    // consistent across both entry points.
    BackHandler {
        if (showHistory) {
            showHistory = false
        } else {
            // Back-out without Done: discard this session's staged (un-committed)
            // loose-pill ADDs. Earlier committed counts are preserved in the DB.
            pillVm.discardStagedCount()
            navController.navigate(Screen.Dashboard.route) {
                popUpTo(0)
                launchSingleTop = true
            }
        }
    }

    LaunchedEffect(dispenseState.error) {
        dispenseState.error?.let {
            showToast(context, it, Toast.LENGTH_SHORT)
            dispenseVm.clearError()
        }
    }

    // Sealed stock bottle confirmed: go back to the batch summary screen.
    LaunchedEffect(dispenseState.navigateToBatchId) {
        dispenseState.navigateToBatchId?.let { targetBatchId ->
            dispenseVm.clearNavigateToBatch()
            navController.navigate(Screen.Batch.createRoute(targetBatchId)) {
                popUpTo(Screen.Batch.route) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    if (dispenseState.showInvalidScanDialog) {
        CommonDialog(
            title = stringResource(R.string.rescan_require),
            message = stringResource(R.string.scan_correct_label),
            confirmText = stringResource(R.string.rescane),
            cancelText = "",
            onConfirm = { dispenseVm.dismissInvalidScanDialog() },
            onCancel = {},
            isSingleButton = true,
        )
    }

    if (dispenseState.showNdcNotFoundDialog) {
        CommonDialog(
            title = stringResource(R.string.rescan_require),
            message = stringResource(R.string.the_scanned_ndc_does_not_match),
            confirmText = stringResource(R.string.rescane),
            cancelText = "",
            onConfirm = { dispenseVm.dismissNdcNotFoundDialog() },
            onCancel = {},
            isSingleButton = true,
        )
    }

    // Substitute drug confirmation: surfaces when the scanned NDC is reported
    // by the server as a generic equivalent of the HL7-expected NDC.
    if (dispenseState.showNdcEquivalenceDialog) {
        CommonDialog(
            title = stringResource(R.string.scan_container_qr_code),
            message = stringResource(R.string.scanned_item_is_a_generic_equivalent_to_the_specific_drug),
            confirmText = stringResource(R.string.substitute),
            cancelText = stringResource(R.string.cancel),
            onConfirm = { dispenseVm.confirmSubstitute() },
            onCancel = { dispenseVm.dismissNdcEquivalenceDialog() },
        )
    }

    // Stock-count flow: user scanned an RX label instead of the NDC container.
    // A blocking dialog is used here (rather than a toast) because stock count
    // never accepts RX labels — the user must always rescan the container.
    if (dispenseState.showRxScannedInStockCountDialog) {
        CommonDialog(
            title = stringResource(R.string.rescan_require),
            message = stringResource(R.string.scan_correct_label),
            confirmText = stringResource(R.string.rescane),
            cancelText = "",
            onConfirm = { dispenseVm.dismissRxScannedInStockCountDialog() },
            onCancel = {},
            isSingleButton = true,
        )
    }

    // Hard PMS NDC mismatch (scanned NDC doesn't match HL7 and isn't a
    // substitute) is surfaced as a non-blocking toast via ndcMismatchToastTick
    // below, not a popup — the popup variant was too disruptive when the user
    // is mid-rescan.
    val ndcMismatchToastText = stringResource(R.string.rescan_ndc_does_not_match_toast)
    LaunchedEffect(dispenseState.ndcMismatchToastTick) {
        if (dispenseState.ndcMismatchToastTick > 0) {
            showToast(context, ndcMismatchToastText, Toast.LENGTH_SHORT)
        }
    }

    // "Scan NDC" toast: fires when the user is in PRE_NDC but scans an RX
    // label instead of the container. handleBarcode() detects the format
    // mismatch and pings the VM, which bumps scanNdcToastTick.
    val scanNdcToastText = stringResource(R.string.scan_ndc_toast)
    LaunchedEffect(dispenseState.scanNdcToastTick) {
        if (dispenseState.scanNdcToastTick > 0) {
            showToast(context, scanNdcToastText, Toast.LENGTH_SHORT)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Camera + pill panel are hidden while the history view is up — matches
        // the legacy PillScanningScreen behavior so CameraX doesn't rebind on
        // rotation-driven lifecycle restarts behind the history list.
        if (!showHistory) {
            if (hasCameraPermission) {
            CameraPreviewSection(
                viewModel = pillVm,
                pills = pillState.detectedPills,
                isCameraPaused = pillVm.cameraPaused.collectAsState().value,
                showGloveIcon = dispenseState.isHazardous,
                onFrame = { imageProxy ->
                    // Only the barcode analyzer reads the frame metadata before the
                    // frame is forwarded to the pill VM (which always closes it). When
                    // pill detection is paused, the VM still closes the frame on the
                    // pause-fast-path inside onFrameCaptured — no leak.
                    if (dispenseState.stage != DispenseStage.COUNTING) {
                        barcodeAnalyzer.analyze(imageProxy) { value, imagePath ->
                            val dispatched = handleBarcode(
                                value = value,
                                imagePath = imagePath,
                                stage = dispenseState.stage,
                                countType = countType,
                                onRx = dispenseVm::onRxBarcodeRead,
                                onNdc = dispenseVm::onNdcBarcodeRead,
                                onRxInNdcStage = dispenseVm::onRxScannedInNdcStage,
                                onRxInStockCount = dispenseVm::onRxScannedInStockCount,
                            )
                            // The analyzer self-pauses on every MLKit hit. If we
                            // dropped the read (false positive, wrong format, or
                            // the COUNTING stage no-op) without surfacing a sheet,
                            // resume immediately — otherwise barcode scanning
                            // would be dead until the user dismissed something
                            // that never appeared.
                            if (!dispatched) barcodeAnalyzer.resume()
                        }
                    }
                    pillVm.onFrameCaptured(imageProxy)
                },
                onFilteredCountChanged = { filteredPillCount = it },
                modifier = Modifier.fillMaxSize(),
                imageFrameWidth = pillState.imageFrameWidth,
                imageFrameHeight = pillState.imageFrameHeight,
                onPreviewSizeKnown = { w, h ->
                    pillVm.initializeInterpreter(retryCount = 2, viewWidth = w, viewHeight = h)
                }
            )
            }

            // ── Pill count panel ─────────────────────────────────────────────
            // Pre-COUNTING the circle tracks live detection: it appears when
            // pills are detected and hides again once the count drops to 0.
            // The hide is debounced so momentary 0-count frames don't flicker.
            //
            // In COUNTING the panel is shown immediately (no detection
            // required): RX + NDC are confirmed, counting is the only step
            // left, so the panel is the primary affordance. Hiding it would
            // leave the screen looking empty and the user with no Done button.
            val pillsLive = filteredPillCount > 0 || pillState.detectedPills.isNotEmpty()
            var showCountCircle by remember { mutableStateOf(false) }
            LaunchedEffect(pillsLive) {
                if (pillsLive) {
                    showCountCircle = true
                } else {
                    delay(COUNT_CIRCLE_HIDE_GRACE_MS)
                    showCountCircle = false
                }
            }
            val showPillPanel = dispenseState.stage == DispenseStage.COUNTING ||
                    showCountCircle
            if (showPillPanel) {
                if (dispenseState.stage == DispenseStage.COUNTING) {
                    // Full pill panel — total / target / circle / Add / Done.
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
                            uiState = pillState,
                            viewModel = pillVm,
                            onEvent = pillVm::onEvent,
                            filteredPillCount = filteredPillCount,
                            onShowHistory = { showHistory = true },
                        )
                    }
                } else {
                    // PRE_RX / PRE_NDC — show only the live count circle. No
                    // Add/Done/total: the user can't commit a count until RX +
                    // NDC are scanned, and the voice prompt guides them there.
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
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        com.rite.pillcounting.feature.dispenseFlow.presentation.compose.CircularCountIndicator(
                            count = filteredPillCount,
                            viewModel = pillVm,
                        )
                    }
                }
            }
        }

        // History list takes over the screen when the user taps Total Count.
        if (showHistory) {
            val totalCount = if (pillState.scanType == CountType.REGULAR.toString()) {
                pillState.stockCountSessionTotal
            } else {
                pillState.txnDetailHistory.sumOf { it.count }
            }
            if (isLandscape) {
                HistoryModeLandscape(
                    navController = navController,
                    scanType = pillState.scanType,
                    targetCount = pillState.targetCount,
                    totalCount = totalCount,
                    txnHistory = pillState.txnDetailHistory,
                    onDeleteTxn = { id ->
                        pillVm.onEvent(PillScanningEvent.TransactionDetailDeleted(id))
                    },
                    viewModel = pillVm,
                    drugName = pillState.drugName,
                    onBack = { showHistory = false },
                )
            } else {
                HistoryModePortrait(
                    navController = navController,
                    scanType = pillState.scanType,
                    targetCount = pillState.targetCount,
                    totalCount = totalCount,
                    txnHistory = pillState.txnDetailHistory,
                    onDeleteTxn = { id ->
                        pillVm.onEvent(PillScanningEvent.TransactionDetailDeleted(id))
                    },
                    viewModel = pillVm,
                    drugName = pillState.drugName,
                    onBack = { showHistory = false },
                )
            }
        }

        // Idle overlay — when the pill VM pauses itself due to inactivity, fade
        // in a "Counting paused" prompt with a Resume button. Matches the
        // legacy screen.
        if (pillState.showIdleOverlay && !showHistory) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppTheme.extendedColors.secondaryBackground.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(24.dp),
                ) {
                    Text(
                        text = stringResource(R.string.counting_paused).uppercase(Locale.ROOT),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Normal,
                        color = AppTheme.extendedColors.textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    ActionButtonPrimary(
                        text = stringResource(R.string.resume).uppercase(Locale.ROOT),
                        onClick = { pillVm.resetIdleOverlay() },
                        modifier = Modifier.width(dimens.dialogButtonWidth),
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }

        if (!showHistory) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    // zIndex above the camera AndroidView so the back arrow's taps
                    // win — the CameraX PreviewView + its pinch-zoom pointerInput
                    // were swallowing taps on the arrow (system back worked, the
                    // on-screen arrow didn't).
                    .zIndex(1f)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(
                    navController = navController,
                    showBox = false,
                    onClick = {
                        // Back-out without Done: discard staged loose-pill ADDs.
                        pillVm.discardStagedCount()
                        navController.navigate(Screen.Dashboard.route) {
                            popUpTo(0)
                            launchSingleTop = true
                        }
                    },
                )
                if (isLandscape) {
                    Spacer(modifier = Modifier.weight(0.3f))
                } else {
                    Spacer(modifier = Modifier.weight(0.6f))
                }
                if (dispenseState.stage == DispenseStage.COUNTING) {
                    StepTitleWithSpeech(
                        stepType = pillStepType,
                        isSoundOverride = isSoundEnabled,
                        titleResOverride = if (countType == CountType.REGULAR.toString()) R.string.scan_open_pills else null,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
            }
        }

        if (dispenseState.isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }

        // Landscape inline-panel width. Matches the legacy ScanBarCodeScreen
        // formula: 30% of screen width clamped to a sensible min/max per device
        // class. Without the clamp the panel becomes uselessly narrow on small
        // phones and excessively wide on large tablets.
        val isTabletDevice = configuration.smallestScreenWidthDp >= 600
        val inlinePanelWidth = (configuration.screenWidthDp.dp * 0.3f).coerceIn(
            if (isTabletDevice) 320.dp else 280.dp,
            if (isTabletDevice) 440.dp else 340.dp,
        )

        // ── RX bottomsheet / inline panel ───────────────────────────────────
        // Non-dismissible: the user must hit Cancel or Proceed.
        if (dispenseState.showRxDetails) {
            if (!isLandscape) {
                VerifyRxDetailsSheet(
                    drugName = dispenseState.drugName,
                    quantity = dispenseState.qty.orEmpty(),
                    bucket = dispenseState.selectedBucketId,
                    ndcNumber = dispenseState.ndc,
                    rxNumber = dispenseState.rxNo.orEmpty(),
                    onCancel = { dispenseVm.onRxCancelled() },
                    onProceed = { dispenseVm.onRxConfirmed() },
                    dismissible = false,
                )
            } else {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(inlinePanelWidth)
                        .background(androidx.compose.ui.graphics.Color.Transparent)
                ) {
                    VerifyRxDetailsInlinePanel(
                        drugName = dispenseState.drugName,
                        quantity = dispenseState.qty.orEmpty(),
                        bucket = dispenseState.selectedBucketId,
                        ndcNumber = dispenseState.ndc,
                        rxNumber = dispenseState.rxNo.orEmpty(),
                        onCancel = { dispenseVm.onRxCancelled() },
                        onProceed = { dispenseVm.onRxConfirmed() },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // ── NDC bottomsheet / inline panel ───────────────────────────────────
        // Same surface treatment as the RX sheet: portrait → ModalBottomSheet,
        // landscape → inline right-side drawer. Non-dismissible: user must hit
        // Cancel or Proceed.
        //
        // Stock count uses VerifyStockBottleSheet (shows Sealed/Open selector).
        // Dispense uses VerifyNdcDetailsSheet (simpler: just NDC + drug name).
        if (dispenseState.showNdcDetails) {
            if (countType == CountType.REGULAR.toString()) {
                val ndcFields = listOf(
                    DialogField(
                        label = stringResource(R.string.ndc_number),
                        value = dispenseState.ndcScannedValue,
                    ),
                    DialogField(
                        label = stringResource(R.string.drugname),
                        value = dispenseState.ndcDrugName,
                        fullWidth = true,
                    ),
                    DialogField(
                        label = stringResource(R.string.quantity),
                        value = dispenseState.ndcPackageQty?.toString().orEmpty(),
                    ),
                    DialogField(
                        label = stringResource(R.string.bucket),
                        value = dispenseState.selectedBucketId,
                    ),
                )
                if (!isLandscape) {
                    VerifyStockBottleSheet(
                        fields = ndcFields,
                        selectedContainerStatus = dispenseState.selectedContainerStatus,
                        onContainerStatusChange = { dispenseVm.onContainerStatusChanged(it) },
                        onCancel = { dispenseVm.onNdcCancelled() },
                        onProceed = { dispenseVm.onNdcConfirmed() },
                        showSealedButtons = true,
                        dismissible = false,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .width(inlinePanelWidth)
                            .background(androidx.compose.ui.graphics.Color.Transparent)
                    ) {
                        VerifyStockBottleInlinePanel(
                            fields = ndcFields,
                            selectedContainerStatus = dispenseState.selectedContainerStatus,
                            onContainerStatusChange = { dispenseVm.onContainerStatusChanged(it) },
                            onCancel = { dispenseVm.onNdcCancelled() },
                            onProceed = { dispenseVm.onNdcConfirmed() },
                            showSealedButtons = true,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            } else {
                if (!isLandscape) {
                    VerifyNdcDetailsSheet(
                        drugName = dispenseState.ndcDrugName,
                        bucket = dispenseState.selectedBucketId,
                        ndcNumber = dispenseState.ndcScannedValue,
                        onCancel = { dispenseVm.onNdcCancelled() },
                        onProceed = { dispenseVm.onNdcConfirmed() },
                        dismissible = false,
                        isHazardous = dispenseState.isHazardous,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .width(inlinePanelWidth)
                            .background(androidx.compose.ui.graphics.Color.Transparent)
                    ) {
                        VerifyNdcDetailsInlinePanel(
                            drugName = dispenseState.ndcDrugName,
                            bucket = dispenseState.selectedBucketId,
                            ndcNumber = dispenseState.ndcScannedValue,
                            onCancel = { dispenseVm.onNdcCancelled() },
                            onProceed = { dispenseVm.onNdcConfirmed() },
                            modifier = Modifier.fillMaxSize(),
                            isHazardous = dispenseState.isHazardous,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Decode a raw scanned barcode and dispatch it to the appropriate stage handler.
 * Mirrors the decoding flow from
// * [com.rite.pillcounting.feature.barcodeScan.presentation.ScanBarCodeScreenContent].
 */
/**
 * Decode a raw scanned barcode and dispatch it to the appropriate stage
 * handler. Returns `true` when the barcode was successfully handed off to the
 * VM (which will open a sheet / show a dialog), `false` when it was dropped
 * (false positive, wrong format, or stage doesn't accept barcodes). The
 * caller uses the return to decide whether to resume the analyzer — without
 * that, a single dropped read leaves the analyzer self-paused forever.
 */
private fun handleBarcode(
    value: String,
    imagePath: String?,
    stage: DispenseStage,
    countType: String,
    onRx: (String, String?) -> Unit,
    onNdc: (String, String?) -> Unit,
    onRxInNdcStage: () -> Unit,
    onRxInStockCount: () -> Unit,
): Boolean {
    if (value.isBlank()) return false
    return when (stage) {
        DispenseStage.PRE_RX -> {
            onRx(value, imagePath)
            true
        }
        DispenseStage.PRE_NDC -> {
            // Pipe-delimited payloads are the RX-label template
            // ({RXNO}|{NDCNO}|{QTY}|{BUCKET}) — never a valid GTIN / GS1.
            // Stock count: show a blocking dialog since RX labels are never valid here.
            // Dispense flow: nudge with a toast and resume scanning.
            if (value.contains('|')) {
                if (countType == CountType.REGULAR.toString()) {
                    onRxInStockCount()
                    return true
                }
                onRxInNdcStage()
                return false
            }
            val decoder = BarcodeDecoder()
            val isGs1 = decoder.isGs1Barcode(value)
            val decoded = if (isGs1) decoder.decode(value) else null
            val extractedGtin = if (isGs1) decoded?.gtin else decoder.toGtin14(value)
            val finalGtin14 = extractedGtin?.let { decoder.toGtin14(it) } ?: ""
            val isInvalid = finalGtin14.isBlank() ||
                    finalGtin14.length != 14 ||
                    !finalGtin14.all { it.isDigit() }
            if (isInvalid) {
                false
            } else {
                onNdc(finalGtin14, imagePath)
                true
            }
        }
        DispenseStage.COUNTING -> false
    }
}
