package com.dispensesure.retail.feature.dispense.presentation

import android.Manifest
import android.content.res.Configuration
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.dispensesure.retail.R
import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.scanning.analyzer.FrameBarcodeAnalyzer
import com.dispensesure.retail.core.scanning.domain.model.DispenseBottle
import com.dispensesure.retail.core.scanning.logic.ScanDecoder
import com.dispensesure.retail.core.scanning.presentation.compose.AddNoteDialog
import com.dispensesure.retail.core.scanning.presentation.compose.BtScannerInputBar
import com.dispensesure.retail.core.scanning.presentation.compose.CameraFeed
import com.dispensesure.retail.core.scanning.presentation.compose.ContainerScanStrip
import com.dispensesure.retail.core.scanning.presentation.compose.CountOverlay
import com.dispensesure.retail.core.scanning.presentation.compose.CountingPausedOverlay
import com.dispensesure.retail.core.scanning.presentation.compose.LiveCountCircle
import com.dispensesure.retail.core.scanning.presentation.compose.count.CountModePhonePortraitDetailsBar
import com.dispensesure.retail.core.scanning.presentation.compose.count.CountModeTopDetailsBar
import com.dispensesure.retail.core.scanning.presentation.compose.count.CountPanelState
import com.dispensesure.retail.core.scanning.presentation.compose.count.HistoryModeLandscape
import com.dispensesure.retail.core.scanning.presentation.compose.count.HistoryModePortrait
import com.dispensesure.retail.core.scanning.presentation.compose.count.InformationPanelSection
import com.dispensesure.retail.core.tourguide.FtueController
import com.dispensesure.retail.core.tourguide.TourActionTimeoutMs
import com.dispensesure.retail.core.tourguide.TourGuideOverlay
import com.dispensesure.retail.core.tourguide.tourTarget
import com.dispensesure.retail.core.utils.common.BarcodeDecoder
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.BackButton
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.CommonDialog
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.showToast
import com.dispensesure.retail.core.utils.common.drugDisplayName
import com.dispensesure.retail.core.utils.common.navigateBackToDashboard
import com.dispensesure.retail.core.utils.compose.BreathingDotsLoader
import com.dispensesure.retail.core.utils.compose.GlobalLoadingOverlay
import com.dispensesure.retail.core.utils.compose.StepTitleWithSpeech
import com.dispensesure.retail.core.utils.compose.VerifyNdcDetailsInlinePanel
import com.dispensesure.retail.core.utils.compose.VerifyNdcDetailsSheet
import com.dispensesure.retail.core.utils.compose.VerifyRxDetailsInlinePanel
import com.dispensesure.retail.core.utils.compose.VerifyRxDetailsSheet
import com.dispensesure.retail.core.utils.permission.rememberPermissionStateDetailed
import com.dispensesure.retail.feature.dispense.domain.model.DispenseCountAction
import com.dispensesure.retail.feature.dispense.domain.model.DispenseCountNavigation
import com.dispensesure.retail.feature.dispense.domain.model.DispenseStage
import com.dispensesure.retail.feature.dispense.presentation.compose.DispenseQueuePanel
import com.dispensesure.retail.feature.dispense.presentation.compose.TargetPillsCountDialog
import com.dispensesure.retail.feature.dispense.presentation.viewmodel.DispenseCountViewModel
import com.dispensesure.retail.feature.dispense.presentation.viewmodel.DispenseFlowViewModel
import com.dispensesure.retail.ui.theme.AppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.math.roundToInt

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
 *                 appears immediately. [DispenseCountViewModel] workflow takes over.
 *                 Glove model is loaded here if the drug is hazardous.
 *
 * Both analyzers run simultaneously in the pre-stages. The pill detector is paused
 * (not stopped) whenever a verification sheet, error dialog, or loading indicator
 * is visible — this avoids stale detections and saves CPU/GPU while the user is
 * looking at modal UI.
 * component (StepTitleWithSpeech) is only rendered during COUNTING.
 */

private const val HAZARDOUS_TAG = "HazardousFlow"

// Total rightward drag that dismisses the landscape Rx panel. Measured from
// where the drag started, so a slow pull counts the same as a flick.
private val INLINE_PANEL_DISMISS_DRAG = 56.dp

@Composable
fun DispenseFlowScreen(
    navController: NavController,
    fromHl7: Boolean = false,
    fromResume: Boolean = false,
    fromQueue: Boolean = false,
    dispenseVm: DispenseFlowViewModel = hiltViewModel(),
    dispenseCountViewModel: DispenseCountViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val dispenseState by dispenseVm.uiState.collectAsState()
    val dispenseCountState by dispenseCountViewModel.uiState.collectAsState()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isTabletDevice = configuration.smallestScreenWidthDp >= 600
    val isSoundEnabled by dispenseCountViewModel.isSoundEnabled.collectAsState()
    val keyboardController = LocalSoftwareKeyboardController.current

    val ftueState = FtueController.state

    // ── BT scanner input ────────────────────────────────────────────────────
    // Tracks the text typed by a Bluetooth HID barcode scanner into the overlay
    // text field. Cleared after each submission so the field is ready for the
    // next scan.
    var btScannerInput by remember { mutableStateOf("") }
    val btFocusRequester = remember { FocusRequester() }

    val cameraPermissionState = rememberPermissionStateDetailed(
        permission = Manifest.permission.CAMERA,
        rationaleTitle = stringResource(R.string.permission_camera_title),
        rationaleMessage = stringResource(R.string.permission_camera_rationale),
    )
    val hasCameraPermission = cameraPermissionState.granted

    // Dispense flow is unusable without the camera — bounce back to Dashboard
    // once the permission request resolves to "denied" (including the
    // permanently-denied "Not now" path), rather than showing a broken/blank
    // screen. Gated on hasResponded so this doesn't fire while the system
    // dialog is still pending, and on showingSettingsDialog so the redirect
    // waits for the user to actually dismiss that dialog instead of racing it.
    LaunchedEffect(
        cameraPermissionState.hasResponded,
        cameraPermissionState.granted,
        cameraPermissionState.showingSettingsDialog,
    ) {
        if (cameraPermissionState.hasResponded &&
            !cameraPermissionState.granted &&
            !cameraPermissionState.showingSettingsDialog
        ) {
            showToast(context, R.string.camera_permission_required)
            navController.navigateBackToDashboard()
        }
    }

    // One-time init for the pill counting workflow side: reset glove state and
    // start listening for the dispenseCountViewModel's own navigation events (Done → Dashboard).
    // Without this collect, hitting "All Done" in the pill panel would
    // fire the confirmation dialog but the user would never actually leave the
    // screen on confirm.
    LaunchedEffect(Unit) {
        dispenseCountViewModel.resetGloveDetection()
        dispenseCountViewModel.navigation.collectLatest { event ->
            when (event) {
                is DispenseCountNavigation.NavigateToDashboard -> {
                    dispenseCountViewModel.resetGloveDetection()
                    // Do NOT reset workflow steps here — that changes pillStepType
                    // while still on COUNTING stage and triggers unwanted TTS speech.
                    // Steps are reset via LaunchedEffect(stage) when stage becomes QUEUE.
                    // Always check for pending dispense items after completing a transaction;
                    // show the queue if any exist, otherwise navigate to Dashboard.
                    dispenseVm.resetToQueueOrNavigateDashboard()
                }
            }
        }
    }

    // Queue mode: initialize the QUEUE stage and start observing dispense transactions.
    // Skip when resuming a specific transaction to avoid the QUEUE stage flash
    // before initializeFromResumedTxn() advances the stage.
    LaunchedEffect(fromQueue) {
        if (fromQueue && !fromResume) dispenseVm.enterQueueMode()
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
    // sealed stock-count screen used. Tapping the Total Count on the pill panel sets
    // this to true.
    var showHistory by rememberSaveable { mutableStateOf(false) }
    // Open-fullscreen image path for the history view. Hoisted to screen level
    // (and saveable) so it survives the portrait↔landscape HistoryMode swap on
    // rotation — otherwise rotating while a preview is open would close it and
    // drop the user back to the history grid.
    var fullScreenImagePath by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(showHistory) {
        if (showHistory) {
            dispenseCountViewModel.pauseIdleTimer()
        } else {
            dispenseCountViewModel.resetIdleTimer()
        }
        dispenseCountViewModel.setCameraPaused(showHistory)
    }

    val pillStepType by dispenseCountViewModel.currentStep.collectAsState()
    val capturedBitmap by dispenseCountViewModel.capturedBitmap.collectAsState()
    val pillSteps by dispenseCountViewModel.steps.collectAsState()
    val glovesDetected by dispenseCountViewModel.glovesDetected.collectAsState()
    val recentDetectionCounts by dispenseCountViewModel.lastTenDetections.collectAsState()
    val isCapturing by dispenseCountViewModel.isCapturing.collectAsState()
    val showFlash by dispenseCountViewModel.showFlash.collectAsState()
    val txnInfo by dispenseCountViewModel.txnInfo.collectAsState()

    // The workflow is only built when counting starts, so the SCAN step has no
    // steps to show. Land the VM on SCAN as the user gets here.
    LaunchedEffect(dispenseState.stage) {
        if (dispenseState.stage == DispenseStage.PRE_NDC) dispenseCountViewModel.enterScanStep()
    }
    // Announce the SCAN step once per visit, the way its title chip used to.
    // Tracks which step was announced rather than a plain flag, so a step arriving
    // after the strip composes still gets its turn. Keyed on the stage so coming
    // back to SCAN announces it again.
    var announcedScanStep by remember(dispenseState.stage) { mutableStateOf<StepState?>(null) }

    // The container-scan step's drug details come from the RX, so the bar only shows
    // once they are loaded.
    val showScanDetailsBar = dispenseState.stage == DispenseStage.PRE_NDC &&
        (dispenseState.drugName.isNotBlank() || dispenseState.ndc.isNotBlank())

    // Reset confirmation. Screen-local: nothing outside this screen needs it.
    var showResetDialog by rememberSaveable { mutableStateOf(false) }

    // Real height of the SCAN step's strip, so the live count circle clears it without
    // a hardcoded guess. Zero for one frame on first composition.
    var scanStripHeight by remember { mutableStateOf(0.dp) }

    // Resume/HL7 entries jump to their real start stage (COUNTING / PRE_NDC)
    // asynchronously via initializeFromResumedTxn()/initializeFromHl7Txn(). Until
    // that resolves, the default stage (PRE_RX → "Scan Rx Label") would flash on
    // screen before snapping to the resumed step. Gate the UI behind a loading
    // overlay while we wait, so the user only ever sees the correct stage.
    val isResumeEntry = remember { fromResume || fromHl7 }
    val awaitingResume = isResumeEntry && !dispenseState.initResolved

    // VIAL step with no photo taken yet: run the barcode analyzer over the live
    // frames so a vial whose RX matches the active transaction can be captured
    // automatically. Once a photo exists (auto or manual) we stop scanning.
    val isVialCaptureStep = dispenseState.stage == DispenseStage.COUNTING &&
            pillStepType == StepState.VIAL &&
            capturedBitmap == null

    // Actively counting pills: keep the barcode scanner live so a rescan of the same NDC
    // (a second bottle) can be detected mid-count — see the bottle-rescan branch below.
    val isCountingScanStep = dispenseState.stage == DispenseStage.COUNTING &&
            (pillStepType == StepState.TARGET_VERIFICATION || pillStepType == StepState.TARGET_REVERIFICATION)

    // Once a hazardous drug is identified, keep the gloves icon visible for the
    // rest of this screen session — even if the user cancels the RX sheet and
    // the dispenseState.isHazardous flag momentarily resets. The icon only clears
    // when the user leaves the screen entirely (remember, not rememberSaveable).
    var sessionHazardous by remember { mutableStateOf(false) }
    if (dispenseState.stage == DispenseStage.COUNTING && dispenseState.isHazardous) {
        sessionHazardous = true
    } else if (dispenseState.stage != DispenseStage.COUNTING) {
        sessionHazardous = false
    }

    // === Pill-VM toasts ===
    val maxCountReachedToastText = stringResource(id = R.string.max_count_reached)
    LaunchedEffect(dispenseCountState.restrictAdd) {
        if (dispenseCountState.restrictAdd) {
            showToast(maxCountReachedToastText)
            dispenseCountViewModel.resetRestrictAdd()
        }
    }
    val noTransactionFoundToastText = stringResource(id = R.string.no_transaction_found)
    LaunchedEffect(dispenseCountState.showNoTransaction) {
        if (dispenseCountState.showNoTransaction) {
            showToast(noTransactionFoundToastText)
            dispenseCountViewModel.resetNoTransaction()
        }
    }
    LaunchedEffect(dispenseCountState.showErrorMessage) {
        dispenseCountState.showErrorMessage?.let { message ->
            showToast(message = message)
            dispenseCountViewModel.clearErrorMessage()
        }
    }

    // === Pill-VM dialogs ===
    if (dispenseCountState.showTargetCountDialog) {
        TargetPillsCountDialog(
            onDismiss = {
                dispenseCountViewModel.setTargetCountDialogShown(false)
                if (fromQueue) {
                    dispenseCountViewModel.resetWorkflowSteps()
                    dispenseVm.resetToQueue()
                } else {
                    navController.navigateBackToDashboard()
                }
            },
            onOkay = { count ->
                dispenseCountViewModel.updateTargetCount(count)
                dispenseCountViewModel.setTargetCountDialogShown(false)
            }
        )
    }

    if (dispenseCountState.showNotesDialog) {
        AddNoteDialog(
            onDismiss = { dispenseCountViewModel.setNoteDialogShown(false) },
            onSkip = { dispenseCountViewModel.onAction(DispenseCountAction.NoteSkip) },
            onSave = { note -> dispenseCountViewModel.onAction(DispenseCountAction.NoteSaved(note)) },
        )
    }

    if (dispenseCountState.showCountMismatchDialog) {
        CommonDialog(
            message = stringResource(R.string.the_counted_quantity_does_not_match_the_target_count),
            title = stringResource(R.string.count_mismatch),
            confirmText = stringResource(R.string.yes),
            cancelText = stringResource(R.string.no),
            onConfirm = { dispenseCountViewModel.moveNextStep() },
            onCancel = {
                dispenseCountViewModel.resetIdleOverlay()
                dispenseCountViewModel.handleDismissDialog()
            },
        )
    }

    if (dispenseCountState.showAddBottleDialog) {
        CommonDialog(
            message = stringResource(R.string.add_new_bottle_confirm_message),
            title = stringResource(R.string.new_bottle_detected),
            confirmText = stringResource(R.string.ok),
            cancelText = stringResource(R.string.cancel),
            onConfirm = { dispenseCountViewModel.onAction(DispenseCountAction.ConfirmAddBottle) },
            onCancel = { dispenseCountViewModel.onAction(DispenseCountAction.CancelAddBottle) },
        )
    }

    if (dispenseCountState.showReplaceBottleDialog) {
        CommonDialog(
            message = stringResource(R.string.replace_bottle_confirm_message),
            title = stringResource(R.string.replace_bottle_detected),
            confirmText = stringResource(R.string.ok),
            cancelText = stringResource(R.string.cancel),
            onConfirm = { dispenseCountViewModel.onAction(DispenseCountAction.ConfirmReplaceBottle) },
            onCancel = { dispenseCountViewModel.onAction(DispenseCountAction.CancelReplaceBottle) },
        )
    }

    if (dispenseCountState.showSkipStepDialog) {
        CommonDialog(
            message = stringResource(R.string.skip_step_message),
            title = stringResource(R.string.skip_step_title),
            confirmText = stringResource(R.string.skip),
            cancelText = "",
            onConfirm = { dispenseCountViewModel.skipBackCount() },
            onCancel = {},
            isSingleButton = true
        )
    }

    // ── Tray color classification popup ─────────────────────────────────────
    // Shown when a tray color is detected during a hazardous transaction and has
    // not been previously classified as hazardous or non-hazardous.
    dispenseCountState.pendingTrayColorForClassification?.let { pendingColor ->
        Log.i(HAZARDOUS_TAG, "Tray classification popup shown | color=${pendingColor.label} | isHazardousTxn=${dispenseState.isHazardous}")
        CommonDialog(
            title = stringResource(R.string.tray_classification_title),
            message = stringResource(R.string.tray_classification_message, pendingColor.label),
            confirmText = stringResource(R.string.yes),
            cancelText = stringResource(R.string.no),
            onConfirm = {
                Log.i(HAZARDOUS_TAG, "Tray classification: user confirmed ${pendingColor.label} as HAZARDOUS → saving")
                dispenseCountViewModel.classifyTrayColor(pendingColor, isHazardous = true)
            },
            onCancel = {
                Log.i(HAZARDOUS_TAG, "Tray classification: user rejected ${pendingColor.label} as hazardous → saving false")
                dispenseCountViewModel.classifyTrayColor(pendingColor, isHazardous = false)
            },
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
            Log.i(HAZARDOUS_TAG, "Stage → COUNTING | txnId=${dispenseState.txnId} | drug=${dispenseState.drugName} | isHazardous=${dispenseState.isHazardous}")
            dispenseCountViewModel.resumePillDetection()
            dispenseCountViewModel.getDrugInfo(forceStartStep = null)
            dispenseCountViewModel.showTxnInfo()
            dispenseCountViewModel.observeResetAvailability()
            // Enable tray color detection for all transactions (hazardous and non-hazardous).
            Log.i(HAZARDOUS_TAG, "Calling setHazardousTransaction(isHazardous=${dispenseState.isHazardous})")
            dispenseCountViewModel.setHazardousTransaction(dispenseState.isHazardous)
            // Load glove model only now that RX + NDC are confirmed and drug is hazardous.
            if (dispenseState.isHazardous) {
                Log.i(HAZARDOUS_TAG, "Hazardous drug — loading glove model")
                dispenseCountViewModel.loadGloveModelAndRebuildAnalyzer()
            }
        } else {
            // PRE_RX / PRE_NDC / QUEUE — run the detector so the voice prompt can react
            // to pills-in-frame, but keep CPU/GPU off when an overlay is active.
            dispenseCountViewModel.resumePillDetection()
            if (dispenseState.stage == DispenseStage.QUEUE) {
                // Clear workflow step icons when returning to queue so COUNTING steps
                // don't linger. Done here (not in the NavigateToDashboard handler) to
                // avoid changing pillStepType while still on COUNTING stage, which
                // would trigger unwanted TTS speech.
                dispenseCountViewModel.resetWorkflowSteps()
            }
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
        dispenseState.isLoading,
    ) {
        val anyOverlay = dispenseState.showRxDetails ||
                dispenseState.showNdcDetails ||
                dispenseState.showNdcNotFoundDialog ||
                dispenseState.showInvalidScanDialog ||
                dispenseState.showNdcEquivalenceDialog ||
                dispenseState.isLoading
        if (anyOverlay) dispenseCountViewModel.pausePillDetection()
        else dispenseCountViewModel.resumePillDetection()
    }

    val barcodeAnalyzer = remember {
        FrameBarcodeAnalyzer(context.applicationContext, isGrayscale = dispenseCountViewModel::isGrayscaleImage)
    }
    DisposableEffect(Unit) {
        onDispose { barcodeAnalyzer.pause() }
    }

    // One-shot toast ticks fire once per scan result, but both scan inputs
    // (camera + BT) would otherwise be ready to read again the instant
    // isLoading clears — same physical label under the camera, or the same
    // BT scanner still primed — so the same result fires again almost
    // instantly and the toast looks continuous. scanCooldownActive pauses
    // BOTH inputs briefly after ANY of these ticks (regardless of which one)
    // so the user has a moment before the next read — folded into the gate
    // LaunchedEffect below so a dialog opening mid-cooldown still wins.
    var scanCooldownActive by remember { mutableStateOf(false) }

    // Pause/resume the barcode analyzer to match the stage. Outside the
    // pre-stages the barcode scanner sits idle; while ANY verification sheet,
    // error dialog, or loading indicator is on top of the camera we don't want
    // to keep firing the analyzer either — otherwise we burn CPU on reads we'd
    // immediately ignore, AND we risk re-triggering the same barcode the moment
    // the dialog closes.
    LaunchedEffect(
        dispenseState.stage,
        isVialCaptureStep,
        isCountingScanStep,
        dispenseState.showRxDetails,
        dispenseState.showNdcDetails,
        dispenseState.showNdcNotFoundDialog,
        dispenseState.showInvalidScanDialog,
        dispenseState.showNdcEquivalenceDialog,
        dispenseState.isLoading,
        scanCooldownActive,
    ) {
        // Pre-stages scan for the RX / NDC labels; the VIAL step scans the vial
        // label to auto-capture when its RX matches the active transaction; the
        // counting steps scan for a same-NDC bottle rescan mid-count.
        val shouldRun = (dispenseState.stage == DispenseStage.QUEUE ||
                dispenseState.stage == DispenseStage.PRE_RX ||
                dispenseState.stage == DispenseStage.PRE_NDC ||
                isVialCaptureStep ||
                isCountingScanStep) &&
                !dispenseState.showRxDetails &&
                !dispenseState.showNdcDetails &&
                !dispenseState.showNdcNotFoundDialog &&
                !dispenseState.showInvalidScanDialog &&
                !dispenseState.showNdcEquivalenceDialog &&
                !dispenseState.isLoading &&
                !scanCooldownActive
        if (shouldRun) barcodeAnalyzer.resume() else barcodeAnalyzer.pause()
    }

    // Track in-flight pill count from the detector (only meaningful in COUNTING).
    var filteredPillCount by remember { mutableStateOf(0) }

    // One snapshot for the count panel, the camera overlay and View All.
    val countPanelState = CountPanelState(
        drugName = dispenseCountState.drugName,
        ndc = dispenseCountState.ndc,
        strength = dispenseCountState.strength,
        bucket = dispenseCountState.bucket,
        dosageForm = dispenseCountState.dosageForm,
        drugImage = dispenseCountState.drugImage,
        totalCount = dispenseCountState.txnDetailHistory.sumOf { txnDetail -> txnDetail.count },
        targetCount = dispenseCountState.targetCount,
        isOpenCount = false,
        stepType = pillStepType,
        steps = pillSteps,
        isVoiceOverEnabled = isSoundEnabled,
        detectedCount = filteredPillCount,
        recentDetectionCounts = recentDetectionCounts,
        isAddCooldown = dispenseCountState.isAddCooldown,
        isIdle = dispenseCountState.showIdleOverlay,
        showGloveIcon = sessionHazardous,
        glovesDetected = glovesDetected,
        hasStill = capturedBitmap != null,
        isCapturing = isCapturing,
    )

    // ── Back handling ───────────────────────────────────────────────────────
    // Device-back priority order:
    //  1. If the history view is open, dismiss it (return to camera + pill panel).
    //  2. If the idle overlay is up, just resume — back on a paused screen must
    //     not leave it.
    //  3. In QUEUE stage: back navigates to Dashboard (queue is the home for dispense).
    //  4. In non-QUEUE stage with fromQueue=true: cancel current scan, return to QUEUE.
    //  5. Otherwise: exit the dispense flow to the Dashboard.
    BackHandler {
        if (showHistory) {
            showHistory = false
        } else if (dispenseCountState.showIdleOverlay) {
            dispenseCountViewModel.resetIdleOverlay()
        } else if (dispenseState.stage == DispenseStage.QUEUE) {
            navController.navigateBackToDashboard()
        } else if (fromQueue && dispenseState.stage == DispenseStage.PRE_RX) {
            // Cancel RX scan before any transaction is created → return to queue list.
            dispenseCountViewModel.resetGloveDetection()
            dispenseCountViewModel.resetWorkflowSteps()
            dispenseVm.resetToQueue()
        } else {
            // Pop back to whichever screen launched this flow (Dashboard for a
            // direct-dispense entry). Fall back to Dashboard only if the back-stack is empty.
            val popped = navController.popBackStack()
            if (!popped) {
                navController.navigateBackToDashboard()
            }
        }
    }

    LaunchedEffect(dispenseState.error) {
        dispenseState.error?.let {
            showToast(it)
            dispenseVm.clearError()
        }
    }

    LaunchedEffect(dispenseState.navigateToDashboard) {
        if (dispenseState.navigateToDashboard) {
            dispenseVm.clearNavigateToDashboard()
            navController.navigateBackToDashboard()
        }
    }

    val invalidScanToastText = stringResource(R.string.scan_correct_label)
    LaunchedEffect(dispenseState.showInvalidScanDialog) {
        if (dispenseState.showInvalidScanDialog) {
            showToast(invalidScanToastText)
            dispenseVm.dismissInvalidScanDialog()
        }
    }

    val ndcNotFoundToastText = stringResource(R.string.the_scanned_ndc_does_not_match)
    LaunchedEffect(dispenseState.showNdcNotFoundDialog) {
        if (dispenseState.showNdcNotFoundDialog) {
            showToast(ndcNotFoundToastText)
            dispenseVm.dismissNdcNotFoundDialog()
        }
    }

    // RX already has a PARTIAL transaction: ask the user whether to continue it.
    // "Yes" advances to PRE_NDC to scan the container; "No" goes to Dashboard.
    if (dispenseState.showContinueRxDialog) {
        CommonDialog(
            title = stringResource(R.string.rx_already_exists_title),
            message = stringResource(R.string.rx_already_exists_message),
            confirmText = stringResource(R.string.yes),
            cancelText = stringResource(R.string.no),
            onConfirm = { dispenseVm.confirmContinueRx() },
            onCancel = {
                dispenseVm.dismissContinueRxDialog()
                if (fromQueue) {
                    dispenseCountViewModel.resetWorkflowSteps()
                    dispenseVm.resetToQueue()
                } else {
                    navController.navigateBackToDashboard()
                }
            },
        )
    }

    // RX transaction is ON_HOLD: block the user with an informational dialog.
    if (dispenseState.showOnHoldDialog) {
        CommonDialog(
            title = stringResource(R.string.rx_on_hold_title),
            message = stringResource(R.string.rx_on_hold_message),
            confirmText = stringResource(R.string.ok),
            cancelText = "",
            onConfirm = { dispenseVm.dismissOnHoldDialog() },
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

    // Hard PMS NDC mismatch (scanned NDC doesn't match HL7 and isn't a
    // substitute) is surfaced as a non-blocking toast via ndcMismatchToastTick
    // below, not a popup — the popup variant was too disruptive when the user
    // is mid-rescan.
    val ndcMismatchToastText = stringResource(R.string.rescan_ndc_does_not_match_toast)
    LaunchedEffect(dispenseState.ndcMismatchToastTick) {
        if (dispenseState.ndcMismatchToastTick > 0) {
            showToast(ndcMismatchToastText)
        }
    }

    // "Scan NDC" toast: fires when the user is in PRE_NDC but scans an RX
    // label instead of the container. handleBarcode() detects the format
    // mismatch and pings the VM, which bumps scanNdcToastTick.
    val scanNdcToastText = stringResource(R.string.scan_ndc_toast)
    LaunchedEffect(dispenseState.scanNdcToastTick) {
        if (dispenseState.scanNdcToastTick > 0) {
            showToast(scanNdcToastText)
        }
    }

    val txnNotFoundToastText = stringResource(R.string.transaction_not_found_toast)
    LaunchedEffect(dispenseState.txnNotFoundToastTick) {
        if (dispenseState.txnNotFoundToastTick > 0) {
            showToast(txnNotFoundToastText)
        }
    }

    // Standalone Rx label whose NDC matched no drug.
    val rxDrugNotFoundToastText = stringResource(R.string.rx_label_drug_not_found_toast)
    LaunchedEffect(dispenseState.rxDrugNotFoundToastTick) {
        if (dispenseState.rxDrugNotFoundToastTick > 0) {
            showToast(rxDrugNotFoundToastText)
        }
    }

    // VIAL step: scanned vial barcode RX doesn't match the active transaction.
    val vialRxMismatchToastText = stringResource(R.string.vial_rx_mismatch_toast)
    LaunchedEffect(dispenseState.vialRxMismatchToastTick) {
        if (dispenseState.vialRxMismatchToastTick > 0) {
            showToast(vialRxMismatchToastText)
        }
    }

    val genericErrorToastText = stringResource(R.string.error_generic)
    LaunchedEffect(dispenseState.errorToastTick) {
        if (dispenseState.errorToastTick > 0) {
            showToast(genericErrorToastText)
        }
    }

    // Scanned RX's most recent transaction is already dispensed — block re-dispensing.
    val rxAlreadyCompletedToastText = stringResource(R.string.rx_already_completed_message)
    LaunchedEffect(dispenseState.rxAlreadyCompletedToastTick) {
        if (dispenseState.rxAlreadyCompletedToastTick > 0) {
            showToast(rxAlreadyCompletedToastText)
        }
    }

    LaunchedEffect(
        dispenseState.ndcMismatchToastTick,
        dispenseState.scanNdcToastTick,
        dispenseState.txnNotFoundToastTick,
        dispenseState.rxDrugNotFoundToastTick,
        dispenseState.vialRxMismatchToastTick,
        dispenseState.rxAlreadyCompletedToastTick,
    ) {
        val anyToastTick = dispenseState.ndcMismatchToastTick +
                dispenseState.scanNdcToastTick +
                dispenseState.txnNotFoundToastTick +
                dispenseState.rxDrugNotFoundToastTick +
                dispenseState.vialRxMismatchToastTick +
                dispenseState.rxAlreadyCompletedToastTick
        if (anyToastTick > 0) {
            scanCooldownActive = true
            delay(1500)
            scanCooldownActive = false
        }
    }

    // Re-focus the BT scanner field whenever all overlays dismiss so the next
    // scan is captured without the user tapping the field.
    val btScannerOverlayActive = dispenseState.showRxDetails ||
            dispenseState.showNdcDetails ||
            dispenseState.showNdcNotFoundDialog ||
            dispenseState.showInvalidScanDialog ||
            dispenseState.showNdcEquivalenceDialog ||
            dispenseState.showOnHoldDialog ||
            dispenseState.isLoading
    LaunchedEffect(btScannerOverlayActive, dispenseState.stage) {
        if (!btScannerOverlayActive && dispenseState.stage != DispenseStage.COUNTING) {
            btScannerInput = ""
            try {
                btFocusRequester.requestFocus()
                keyboardController?.hide()
            } catch (_: Exception) {}
        }
    }

    if (showResetDialog) {
        CommonDialog(
            title = stringResource(R.string.reset_count_title),
            message = stringResource(R.string.reset_count_message),
            confirmText = stringResource(R.string.reset),
            cancelText = stringResource(R.string.cancel),
            onCancel = { showResetDialog = false },
            onConfirm = {
                showResetDialog = false
                // One call on the view model's scope. Disposing the screen mid-reset
                // must not leave a wiped transaction with the stage on COUNTING.
                dispenseVm.returnToContainerScan {
                    // Delete first, then move the stage — otherwise the SCAN strip
                    // renders against counts that are still on their way out.
                    // A failed reset keeps the current step (the VM shows the error).
                    dispenseCountViewModel.resetTransaction().also { ok ->
                        // Land on SCAN before the stage flips. The stage effect would do
                        // it a frame later, by which time the strip has already composed
                        // against the step we just left and spent its announcement on it.
                        // The effect repeating the call after the flip is harmless.
                        if (ok) {
                            dispenseCountViewModel.enterScanStep()
                            // The analyzer self-pauses after every read; re-arm it for the rescan.
                            barcodeAnalyzer.resume()
                        }
                    }
                }
            },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Any touch counts as activity. Initial pass and no consume, so the
            // camera's pinch-zoom and the back arrow still get the event.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        dispenseCountViewModel.noteCountActivity()
                    }
                }
            }
    ) {
        // Camera + pill panel are hidden while the history view is up — matches
        // the sealed stock-count screen's behavior so CameraX doesn't rebind on
        // rotation-driven lifecycle restarts behind the history list.
        if (!showHistory) {
            if (hasCameraPermission) {
                CameraFeed(
                    isCameraPaused = dispenseCountViewModel.cameraPaused.collectAsState().value,
                    isStillShown = capturedBitmap != null,
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
                                    onRx = dispenseVm::onRxBarcodeRead,
                                    onNdc = dispenseVm::onNdcBarcodeRead,
                                    onRxInNdcStage = dispenseVm::onRxScannedInNdcStage,
                                )
                                // The analyzer self-pauses on every MLKit hit. If we
                                // dropped the read (false positive, wrong format, or
                                // the COUNTING stage no-op) without surfacing a sheet,
                                // resume immediately — otherwise barcode scanning
                                // would be dead until the user dismissed something
                                // that never appeared.
                                if (!dispatched) barcodeAnalyzer.resume()
                            }
                        } else if (
                            pillStepType == StepState.TARGET_VERIFICATION ||
                            pillStepType == StepState.TARGET_REVERIFICATION
                        ) {
                            // Actively counting pills: keep the scanner live so a rescan of the
                            // same NDC (a second bottle) can be detected mid-count. The analyzer
                            // self-pauses on each hit; always resume immediately afterward since
                            // this hook never itself surfaces a full-screen sheet that would
                            // otherwise re-trigger on the same barcode.
                            barcodeAnalyzer.analyze(imageProxy) { value, imagePath ->
                                dispenseCountViewModel.onNdcRescannedDuringCount(value, imagePath)
                                barcodeAnalyzer.resume()
                            }
                        } else if (
                            pillStepType == StepState.VIAL && capturedBitmap == null
                        ) {
                            // Conditions are read live here (not via the precomputed
                            // isVialCaptureStep) because this lambda is captured once
                            // by CameraFeed's frame collector — a plain
                            // captured Boolean would be frozen at first composition.
                            // VIAL step: read the vial label and auto-capture the
                            // photo when its RX matches the active transaction. The
                            // analyzer self-pauses on each hit; on a match we leave it
                            // paused (capturedBitmap will hide the live view), on a
                            // miss we resume to keep scanning. Manual capture still
                            // works via the camera button.
                            barcodeAnalyzer.analyze(imageProxy) { value, _ ->
                                val matched = dispenseVm.onVialBarcodeRead(value)
                                if (matched) {
                                    // Auto-capture: always grab the still on a match.
                                    // Only auto-commit (as if Done was tapped) when
                                    // VIAL is the LAST workflow step — then the
                                    // transaction completes without a manual tap. When
                                    // VIAL is followed by more steps, just show the
                                    // captured image and wait for the user to tap Done
                                    // before advancing. Manual capture (camera button)
                                    // always requires a Done tap.
                                    dispenseCountViewModel.captureImage(autoConfirm = dispenseCountViewModel.isVialLastStep())
                                } else {
                                    barcodeAnalyzer.resume()
                                }
                            }
                        }
                        dispenseCountViewModel.onFrameCaptured(imageProxy)
                    },
                    onCameraHelperReady = dispenseCountViewModel::attachCameraHelper,
                    onCameraReady = {
                        dispenseCountViewModel.initializeInterpreter()
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .tourTarget(ftueState, "camera_preview"),
                )
                CountOverlay(
                    engineStates = dispenseCountViewModel.engineState,
                    runningTotal = countPanelState.totalCount,
                    targetCount = dispenseCountState.targetCount,
                    stepType = pillStepType,
                    // Red excess dots once the dispense txn is loaded.
                    showExcessPills = txnInfo != null,
                    capturedStill = capturedBitmap,
                    showFlash = showFlash,
                    addPops = dispenseCountViewModel.addPopEvents,
                    onFilteredPills = { filteredPills ->
                        dispenseCountViewModel.updateFilteredPills(filteredPills)
                        filteredPillCount = filteredPills.size
                    },
                    modifier = Modifier.fillMaxSize(),
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
            // Suppress the pre-COUNTING pill panel while a resume/HL7 entry is
            // still resolving its real stage — we keep the live camera visible
            // under the loading scrim, but the wrong-stage overlay must not bleed
            // through it.
            if (!awaitingResume) {
                if (dispenseState.stage == DispenseStage.COUNTING) {
                    // Full pill panel — total / target / circle / Add / Done. Every
                    // counting step, VIAL included, uses the full-bleed overlay:
                    // details bar on top, its own control over the feed, strip at
                    // the bottom.
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxSize()
                    ) {
                        InformationPanelSection(
                            state = countPanelState,
                            onAdd = {
                                dispenseCountViewModel.onAction(
                                    DispenseCountAction.AddTransactionDetailClicked(filteredPillCount, pillStepType)
                                )
                            },
                            onDone = {
                                dispenseCountViewModel.onAction(
                                    DispenseCountAction.FinalDone(
                                        stepType = pillStepType,
                                        totalCount = countPanelState.totalCount,
                                    )
                                )
                            },
                            onShowHistory = { showHistory = true },
                            onCountSteady = dispenseCountViewModel::playCountSoundIfEnabled,
                            onCapture = { dispenseCountViewModel.captureImage() },
                            onRedoCapture = { dispenseCountViewModel.redoCaptureImage() },
                            onSaveCapture = { dispenseCountViewModel.saveCaptureImage() },
                            onReset = if (dispenseCountState.canReset) {
                                { showResetDialog = true }
                            } else null,
                        )
                    }
                } else {
                    // PRE_RX / PRE_NDC — show only the live count circle. No
                    // Add/Done/total: the user can't commit a count until RX +
                    // NDC are scanned, and the voice prompt guides them there.
                    // In portrait the circle clears the SCAN step's stepper, which
                    // sits along the bottom edge.
                    LiveCountCircle(
                        detectedCount = filteredPillCount,
                        pillsInView = filteredPillCount > 0 || dispenseCountState.detectedPills.isNotEmpty(),
                        recentDetectionCounts = recentDetectionCounts,
                        isAddCooldown = dispenseCountState.isAddCooldown,
                        isIdle = dispenseCountState.showIdleOverlay,
                        onCountSteady = dispenseCountViewModel::playCountSoundIfEnabled,
                        isLandscape = isLandscape,
                        bottomInset = if (dispenseState.stage == DispenseStage.PRE_NDC) {
                            scanStripHeight
                        } else {
                            0.dp
                        },
                    )
                }
            }
        }

        // ── SCAN step strip ───────────────────────────────────────────────────
        // Details bar on top, workflow stepper along the bottom, so the container
        // scan looks like the counting steps that follow it. The drug details are
        // the ones the PMS order / RX label already gave us — the pill VM has no
        // transaction loaded this early.
        if (dispenseState.stage == DispenseStage.PRE_NDC && !showHistory && !awaitingResume) {
            if (showScanDetailsBar) {
                // Same default the counting steps apply, so the bucket does not blank
                // out when a reset lands back on the container scan.
                val defaultBucket = stringResource(R.string.default_bucket)
                // Phone portrait drops the image, strength and bucket onto a second
                // row. Same bar as the steps that follow, so the header does not
                // change shape once the container is scanned.
                if (!isTabletDevice && !isLandscape) {
                    CountModePhonePortraitDetailsBar(
                        ndc = dispenseState.ndc,
                        drugName = dispenseState.drugName,
                        strength = dispenseState.ndcStrength.orEmpty(),
                        bucket = dispenseState.selectedBucketId.ifBlank { defaultBucket },
                        dosageForm = dispenseState.ndcDosageForm.orEmpty(),
                        drugImage = dispenseState.drugImage,
                        showGloveIcon = false,
                        glovesDetected = false,
                    )
                } else {
                    CountModeTopDetailsBar(
                        ndc = dispenseState.ndc,
                        drugName = dispenseState.drugName,
                        strength = dispenseState.ndcStrength.orEmpty(),
                        bucket = dispenseState.selectedBucketId.ifBlank { defaultBucket },
                        drugImage = dispenseState.drugImage,
                    )
                }
            }
            // The same bottom strip the counting steps use. Every counting widget is
            // off — nothing has been counted before a container is scanned — so the
            // steps are all it carries.
            ContainerScanStrip(
                steps = pillSteps,
                currentStep = pillStepType,
                isVoiceOverEnabled = isSoundEnabled,
                isTablet = isTabletDevice,
                isLandscape = isLandscape,
                autoRevealCurrentStep = announcedScanStep != pillStepType,
                onAutoRevealed = { announcedScanStep = pillStepType },
                isFixed = true,
                onHeightKnown = { stripHeight -> scanStripHeight = stripHeight },
            )
        }

        // ── QUEUE stage overlay ───────────────────────────────────────────────
        // Shows the dispense transaction list on top of the camera preview.
        // Layout adapts per device form factor and orientation.
        if (dispenseState.stage == DispenseStage.QUEUE && !showHistory) {
            if (isLandscape) {
                // Landscape (tablet or phone): panel on the right side.
                val queuePanelWidth = if (isTabletDevice) 0.4f else 0.45f
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .fillMaxWidth(queuePanelWidth)
                        .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                        .background(AppTheme.extendedColors.secondaryBackground)
                ) {
                    DispenseQueuePanel(
                        items = dispenseState.queueItems,
                        selectedFilter = dispenseState.selectedQueueFilter,
                        onFilterSelected = { dispenseVm.setQueueFilter(it) },
                        onItemClick = { txnId -> dispenseVm.resumeFromQueue(txnId) },
                        onHomeClick = { navController.navigateBackToDashboard() },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else if (isTabletDevice) {
                // Tablet portrait: panel anchored at bottom half.
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(0.5f)
                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                        .background(AppTheme.extendedColors.secondaryBackground)
                ) {
                    DispenseQueuePanel(
                        items = dispenseState.queueItems,
                        selectedFilter = dispenseState.selectedQueueFilter,
                        onFilterSelected = { dispenseVm.setQueueFilter(it) },
                        onItemClick = { txnId -> dispenseVm.resumeFromQueue(txnId) },
                        onHomeClick = { navController.navigateBackToDashboard() },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                // Phone portrait: bottom sheet anchored at bottom half.
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(0.55f)
                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                        .background(AppTheme.extendedColors.secondaryBackground)
                ) {
                    DispenseQueuePanel(
                        items = dispenseState.queueItems,
                        selectedFilter = dispenseState.selectedQueueFilter,
                        onFilterSelected = { dispenseVm.setQueueFilter(it) },
                        onItemClick = { txnId -> dispenseVm.resumeFromQueue(txnId) },
                        onHomeClick = { navController.navigateBackToDashboard() },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // History list takes over the screen when the user taps Total Count.
        if (showHistory) {
            if (isLandscape) {
                HistoryModeLandscape(
                    navController = navController,
                    showTargetCount = countPanelState.isFixed,
                    targetCount = dispenseCountState.targetCount,
                    totalCount = countPanelState.totalCount,
                    txnHistory = dispenseCountState.txnDetailHistory,
                    onDeleteTxn = { id ->
                        dispenseCountViewModel.onAction(DispenseCountAction.TransactionDetailDeleted(id))
                    },
                    drugName = dispenseCountState.drugName,
                    onBack = { showHistory = false },
                    selectedImagePath = fullScreenImagePath,
                    onImageSelected = { fullScreenImagePath = it },
                )
            } else {
                HistoryModePortrait(
                    navController = navController,
                    showTargetCount = countPanelState.isFixed,
                    targetCount = dispenseCountState.targetCount,
                    totalCount = countPanelState.totalCount,
                    txnHistory = dispenseCountState.txnDetailHistory,
                    onDeleteTxn = { id ->
                        dispenseCountViewModel.onAction(DispenseCountAction.TransactionDetailDeleted(id))
                    },
                    drugName = dispenseCountState.drugName,
                    onBack = { showHistory = false },
                    selectedImagePath = fullScreenImagePath,
                    onImageSelected = { fullScreenImagePath = it },
                )
            }
        }

        // Idle overlay — when the pill VM pauses itself due to inactivity, fade
        // in a "Counting paused" prompt with a Resume button. Matches the
        // legacy screen.
        if (dispenseCountState.showIdleOverlay && !showHistory) {
            CountingPausedOverlay(onResume = { dispenseCountViewModel.resetIdleOverlay() })
        }

        if (!showHistory) {
            // In landscape the right side is occupied by a panel (pill count / queue).
            // Pad the header end by the same fraction so Alignment.Center lands in
            // the middle of the visible camera area rather than the full screen width.
            val headerEndPadding = if (isLandscape) {
                val screenW = configuration.screenWidthDp.dp
                when (dispenseState.stage) {
                    DispenseStage.COUNTING -> screenW * 0.3f
                    DispenseStage.QUEUE -> screenW * if (isTabletDevice) 0.4f else 0.45f
                    else -> 0.dp
                }
            } else 0.dp
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    // zIndex above the camera AndroidView so the back arrow's taps
                    // win — the CameraX PreviewView + its pinch-zoom pointerInput
                    // were swallowing taps on the arrow (system back worked, the
                    // on-screen arrow didn't).
                    .zIndex(1f)
                    // Camera preview stays full-bleed; only the header chrome is
                    // inset so the back arrow and title clear the notch.
                    .displayCutoutPadding()
                    .padding(top = 8.dp, bottom = 8.dp, end = headerEndPadding),
            ) {
                // During COUNTING the back arrow sits in the left gutter of the
                // details bar (NDC on top, drug name below). The arrow's own 10.dp
                // internal padding makes its bounding box taller than that two-line
                // block, so a plain CenterStart drops the arrow to the drug-name
                // line. Pin it to the top and nudge up so the arrow centre lines up
                // with the centre of the NDC / drug-name block instead. Other stages
                // keep the arrow centred against the step-title header. SCAN aligns the
                // same way, but only when it actually draws that bar.
                val centreOnDetailsBar = dispenseState.stage == DispenseStage.COUNTING ||
                    showScanDetailsBar
                BackButton(
                    navController = navController,
                    showBox = false,
                    modifier = if (centreOnDetailsBar) {
                        Modifier
                            .align(Alignment.TopStart)
                            .offset(y = (-8).dp)
                    } else {
                        Modifier.align(Alignment.CenterStart)
                    },
                    onClick = {
                        if (fromQueue && dispenseState.stage == DispenseStage.PRE_RX) {
                            dispenseCountViewModel.resetGloveDetection()
                            dispenseCountViewModel.resetWorkflowSteps()
                            dispenseVm.resetToQueue()
                        } else {
                            navController.navigateBackToDashboard()
                        }
                    },
                )
                val headerStepType = when (dispenseState.stage) {
                    DispenseStage.QUEUE -> StepState.RX_LABEL
                    DispenseStage.PRE_RX -> StepState.RX_LABEL
                    DispenseStage.PRE_NDC -> StepState.SCAN
                    DispenseStage.COUNTING -> pillStepType
                }
                Box(modifier = Modifier.align(Alignment.Center)) {
                    // Also suppress while a resume/HL7 entry is still resolving its
                    // real start stage: otherwise the default PRE_RX header would
                    // both flash AND speak "Scan Rx Label" before snapping to the
                    // resumed step. Not composing it here prevents the TTS entirely.
                    //
                    // Every step from the container scan onwards carries the strip,
                    // whose stepper announces the step — so no title chip from
                    // PRE_NDC through COUNTING (VIAL was the last one still showing
                    // it). QUEUE and PRE_RX keep theirs.
                    val suppressHeaderTitle = dispenseState.stage == DispenseStage.COUNTING ||
                        dispenseState.stage == DispenseStage.PRE_NDC ||
                        awaitingResume
                    if (!suppressHeaderTitle) {
                        StepTitleWithSpeech(
                            stepType = headerStepType,
                            isSoundOverride = isSoundEnabled,
                        )
                    }
                }
            }
        }

        // BT scanner input bar — visible during QUEUE, PRE_RX and PRE_NDC while no modal
        // overlay is active. Also hidden during scanCooldownActive so the BT scanner
        // doesn't resubmit the same still-primed read while the camera is paused too.
        if (!showHistory &&
            dispenseState.stage != DispenseStage.COUNTING &&
            !btScannerOverlayActive &&
            !scanCooldownActive &&
            !awaitingResume
        ) {
            BtScannerInputBar(
                input = btScannerInput,
                onInputChange = { btScannerInput = it },
                onSubmit = { barcode ->
                    if (barcode.isNotBlank()) {
                        val dispatched = handleBarcode(
                            value = barcode,
                            imagePath = null,
                            stage = dispenseState.stage,
                            onRx = dispenseVm::onRxBarcodeRead,
                            onNdc = dispenseVm::onNdcBarcodeRead,
                            onRxInNdcStage = dispenseVm::onRxScannedInNdcStage,
                        )
                        if (!dispatched) barcodeAnalyzer.resume()
                    }
                },
                focusRequester = btFocusRequester,
                modifier = Modifier.align(Alignment.TopStart),
            )
        }

        if (dispenseState.isLoading) {
            BreathingDotsLoader(modifier = Modifier.align(Alignment.Center))
        }

        // Landscape inline-panel width. Matches the legacy ScanBarCodeScreen
        // formula: 30% of screen width clamped to a sensible min/max per device
        // class. Without the clamp the panel becomes uselessly narrow on small
        // phones and excessively wide on large tablets.
        val inlinePanelWidth = (configuration.screenWidthDp.dp * 0.3f).coerceIn(
            if (isTabletDevice) 320.dp else 280.dp,
            if (isTabletDevice) 440.dp else 340.dp,
        )

        // ── RX bottomsheet / inline panel ───────────────────────────────────
        // Dismissible: swipe-down / outside-tap / back all act as Cancel. No
        // transaction exists yet at this point, so nothing is orphaned.
        if (dispenseState.showRxDetails) {
            if (!isLandscape) {
                VerifyRxDetailsSheet(
                    drugName = drugDisplayName(dispenseState.drugName),
                    quantity = dispenseState.qty.orEmpty(),
                    bucket = dispenseState.selectedBucketId,
                    ndcNumber = dispenseState.ndc,
                    rxNumber = dispenseState.rxNo.orEmpty(),
                    strength = dispenseState.ndcStrength.orEmpty(),
                    drugImage = dispenseState.drugImage,
                    onCancel = { dispenseVm.onRxCancelled() },
                    onProceed = { dispenseVm.onRxConfirmed() },
                    // The tour only leaves this sheet through its own Next / Skip.
                    dismissible = !ftueState.isActive,
                )
            } else {
                // How far the panel has been dragged toward the right edge.
                var panelDragPx by remember { mutableFloatStateOf(0f) }

                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(inlinePanelWidth)
                        .offset { IntOffset(panelDragPx.roundToInt(), 0) }
                        .background(Color.Transparent)
                        // The panel tracks the finger; releasing past the
                        // threshold cancels, anything shorter snaps back. Gives
                        // landscape the same escape as the portrait sheet.
                        .pointerInput(Unit) {
                            val dismissPx = INLINE_PANEL_DISMISS_DRAG.toPx()
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    if (panelDragPx > dismissPx && !ftueState.isActive) {
                                        dispenseVm.onRxCancelled()
                                    } else {
                                        panelDragPx = 0f
                                    }
                                },
                                onDragCancel = { panelDragPx = 0f },
                            ) { _, dragAmount ->
                                panelDragPx = (panelDragPx + dragAmount).coerceAtLeast(0f)
                            }
                        }
                ) {
                    VerifyRxDetailsInlinePanel(
                        drugName = drugDisplayName(dispenseState.drugName),
                        quantity = dispenseState.qty.orEmpty(),
                        bucket = dispenseState.selectedBucketId,
                        ndcNumber = dispenseState.ndc,
                        rxNumber = dispenseState.rxNo.orEmpty(),
                        strength = dispenseState.ndcStrength.orEmpty(),
                        onCancel = { dispenseVm.onRxCancelled() },
                        onProceed = { dispenseVm.onRxConfirmed() },
                        modifier = Modifier.fillMaxSize(),
                        drugImage = dispenseState.drugImage
                    )
                }
            }
        }

        // ── NDC bottomsheet / inline panel ───────────────────────────────────
        // Same surface treatment as the RX sheet: portrait → ModalBottomSheet,
        // landscape → inline right-side drawer. Non-dismissible: user must hit
        // Cancel or Proceed.
        if (dispenseState.showNdcDetails) {
            if (!isLandscape) {
                VerifyNdcDetailsSheet(
                    drugName = drugDisplayName(dispenseState.ndcDrugName),
                    bucket = dispenseState.selectedBucketId,
                    ndcNumber = dispenseState.ndcScannedValue,
                    onCancel = { dispenseVm.onNdcCancelled() },
                    onProceed = { dispenseVm.onNdcConfirmed() },
                    dismissible = false,
                    isHazardous = dispenseState.isHazardous,
                    strength = dispenseState.ndcStrength.orEmpty(),
                    dosageForm = dispenseState.ndcDosageForm.orEmpty(),
                    drugImage = dispenseState.drugImage
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
                        drugName = drugDisplayName(dispenseState.ndcDrugName),
                        bucket = dispenseState.selectedBucketId,
                        ndcNumber = dispenseState.ndcScannedValue,
                        onCancel = { dispenseVm.onNdcCancelled() },
                        onProceed = { dispenseVm.onNdcConfirmed() },
                        modifier = Modifier.fillMaxSize(),
                        isHazardous = dispenseState.isHazardous,
                        strength = dispenseState.ndcStrength.orEmpty(),
                        dosageForm = dispenseState.ndcDosageForm.orEmpty(),
                        drugImage = dispenseState.drugImage
                    )
                }
            }
        }

        // Loading gate for resume/HL7 entries — drawn last so it sits on top
        // until the real start stage is resolved. Rather than an opaque white
        // cover (which reads as a frozen/blank screen), we keep the live camera
        // visible underneath and lay a light dark scrim + loader over it, so it
        // looks like the session is loading over the camera. The wrong-stage
        // PRE_RX overlays are suppressed separately (see showPillPanel /
        // BtScannerInputBar) so nothing incorrect bleeds through the scrim.
        GlobalLoadingOverlay(
            isVisible = awaitingResume,
            backgroundColor = Color.Black.copy(alpha = 0.4f),
        )

        // Skip the screen-root overlay while RX/NDC verify is up: in landscape,
        // VerifyRxDetailsInlinePanel renders inline in this same window with its own
        // TourGuideOverlay, and both would otherwise render duplicate scrims/tooltips
        // for the same "verify_rx_proceed" target.
        // d_note is drawn by the Add Note dialog's own window (TourDialogHost).
        if (!dispenseState.showRxDetails && !dispenseState.showNdcDetails && ftueState.currentStep?.id != "d_note") {
            TourGuideOverlay(
                state = ftueState,
                modifier = Modifier.fillMaxSize(),
                onNext = {
                    when (ftueState.currentStep?.id) {
                        "d_scan_rx" -> {
                            // useDummyRxData ignores calls mid-load or in another stage; wait for a state it accepts.
                            withTimeout(TourActionTimeoutMs) {
                                dispenseVm.uiState.first {
                                    it.showRxDetails || (!it.isLoading && (it.stage == DispenseStage.PRE_RX || it.stage == DispenseStage.QUEUE))
                                }
                            }
                            if (!dispenseVm.uiState.value.showRxDetails) dispenseVm.useDummyRxData()
                            withTimeout(TourActionTimeoutMs) { dispenseVm.uiState.first { it.showRxDetails } }
                        }
                        "d_scan_container" -> {
                            // The Rx sheet's Proceed creates the txn asynchronously.
                            // A retry may find the previous attempt already past PRE_NDC.
                            if (dispenseVm.uiState.value.stage != DispenseStage.COUNTING) {
                                withTimeout(TourActionTimeoutMs) { dispenseVm.uiState.first { it.stage == DispenseStage.PRE_NDC } }
                                dispenseVm.useDummyNdcData()
                            }
                            withTimeout(TourActionTimeoutMs) {
                                dispenseVm.uiState.first { it.stage == DispenseStage.COUNTING || it.showNdcDetails }
                            }
                            if (dispenseVm.uiState.value.showNdcDetails) {
                                dispenseVm.onNdcConfirmed()
                                withTimeout(TourActionTimeoutMs) { dispenseVm.uiState.first { it.stage == DispenseStage.COUNTING } }
                            }
                            // The count circle only exists once the workflow is on a counting step;
                            // without this the next step would spotlight nothing.
                            withTimeout(TourActionTimeoutMs) { dispenseCountViewModel.currentStep.first { it in countingSteps } }
                        }
                        "d_count" -> {
                            dispenseCountViewModel.dummyCompleteCount()
                            withTimeout(TourActionTimeoutMs) {
                                dispenseCountViewModel.currentStep.first { it == StepState.VIAL || it !in countingSteps }
                            }
                            // The vial photo isn't a tour step; save a demo one so the count can finish.
                            if (dispenseCountViewModel.currentStep.value == StepState.VIAL) dispenseCountViewModel.captureVialForTour()
                        }
                    }
                },
            )
        }
    }
}

/**
 * Decode a raw scanned barcode and dispatch it to the appropriate stage handler.
 * Mirrors the decoding flow from
// * [com.dispensesure.retail.feature.barcodeScan.presentation.ScanBarCodeScreenContent].
 */
/**
 * Decode a raw scanned barcode and dispatch it to the appropriate stage
 * handler. Returns `true` when the barcode was successfully handed off to the
 * VM (which will open a sheet / show a dialog), `false` when it was dropped
 * (false positive, wrong format, or stage doesn't accept barcodes). The
 * caller uses the return to decide whether to resume the analyzer — without
 * that, a single dropped read leaves the analyzer self-paused forever.
 */
internal fun handleBarcode(
    value: String,
    imagePath: String?,
    stage: DispenseStage,
    onRx: (String, String?) -> Unit,
    onNdc: (String, String?, DispenseBottle?) -> Unit,
    onRxInNdcStage: () -> Unit,
): Boolean {
    // No real scans during the tour: the demo data is fed in by the tour card's Next.
    if (value.isBlank() || FtueController.state.isActive) return false
    return when (stage) {
        DispenseStage.QUEUE,
        DispenseStage.PRE_RX -> {
            onRx(value, imagePath)
            true
        }
        DispenseStage.PRE_NDC -> {
            // Pipe-delimited payloads are the RX-label template
            // ({RXNO}|{NDCNO}|{QTY}|{BUCKET}) — never a valid GTIN / GS1.
            // Nudge with a toast and resume scanning.
            if (value.contains('|')) {
                onRxInNdcStage()
                return false
            }
            val scan = ScanDecoder(BarcodeDecoder()).decode(value)
            val finalGtin14 = scan.gtin14 ?: ""
            val isInvalid = finalGtin14.isBlank() ||
                    finalGtin14.length != 14 ||
                    !finalGtin14.all { it.isDigit() }
            if (isInvalid) {
                false
            } else {
                // First-bottle info for this txn: a plain (non-GS1) barcode carries no
                // lot/exp/serial, so the bottle entry is count-only in that case.
                val firstBottle = DispenseBottle(
                    lotNumber = scan.lot,
                    expirationDate = scan.expiry,
                    serialNumber = scan.serial,
                    barcodeImagePath = imagePath,
                )
                onNdc(finalGtin14, imagePath, firstBottle)
                true
            }
        }
        DispenseStage.COUNTING -> false
    }
}

private val countingSteps = setOf(StepState.CONTAINER_INITIATE, StepState.TARGET_VERIFICATION, StepState.TARGET_REVERIFICATION)
