package com.dispensesure.retail.feature.dispense.presentation

import android.Manifest
import android.content.res.Configuration
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.dispensesure.retail.R
import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.region.LocalRegion
import com.dispensesure.retail.core.region.wording
import com.dispensesure.retail.core.scanning.analyzer.FrameBarcodeAnalyzer
import com.dispensesure.retail.core.scanning.logic.ScanDecoder
import com.dispensesure.retail.core.scanning.presentation.compose.BtScannerInputBar
import com.dispensesure.retail.core.scanning.presentation.compose.CameraFeed
import com.dispensesure.retail.core.scanning.presentation.compose.CountOverlay
import com.dispensesure.retail.core.scanning.presentation.compose.CountingPausedOverlay
import com.dispensesure.retail.core.scanning.presentation.compose.LiveCountCircle
import com.dispensesure.retail.core.scanning.presentation.compose.count.CountPanelState
import com.dispensesure.retail.core.tourguide.FtueController
import com.dispensesure.retail.core.tourguide.TourActionTimeoutMs
import com.dispensesure.retail.core.tourguide.TourGuideOverlay
import com.dispensesure.retail.core.tourguide.tourTarget
import com.dispensesure.retail.core.utils.common.BarcodeDecoder
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.showToast
import com.dispensesure.retail.core.utils.common.navigateBackToDashboard
import com.dispensesure.retail.core.utils.compose.BreathingDotsLoader
import com.dispensesure.retail.core.utils.compose.GlobalLoadingOverlay
import com.dispensesure.retail.core.utils.permission.rememberPermissionStateDetailed
import com.dispensesure.retail.feature.dispense.domain.BackPressOutcome
import com.dispensesure.retail.feature.dispense.domain.BarcodeRoute
import com.dispensesure.retail.feature.dispense.domain.DispenseBackPress
import com.dispensesure.retail.feature.dispense.domain.DispenseBarcodeRouting
import com.dispensesure.retail.feature.dispense.domain.model.DispenseCountAction
import com.dispensesure.retail.feature.dispense.domain.model.DispenseCountEffect
import com.dispensesure.retail.feature.dispense.domain.model.DispenseFlowAction
import com.dispensesure.retail.feature.dispense.domain.model.DispenseFlowEffect
import com.dispensesure.retail.feature.dispense.domain.model.DispenseStage
import com.dispensesure.retail.feature.dispense.presentation.stage.ContainerScanStage
import com.dispensesure.retail.feature.dispense.presentation.stage.CountHistory
import com.dispensesure.retail.feature.dispense.presentation.stage.CountingStage
import com.dispensesure.retail.feature.dispense.presentation.stage.QueueStage
import com.dispensesure.retail.feature.dispense.presentation.stage.RxScanStage
import com.dispensesure.retail.feature.dispense.presentation.viewmodel.DispenseCountViewModel
import com.dispensesure.retail.feature.dispense.presentation.viewmodel.DispenseFlowViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

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
 * The spoken step title (StepTitleWithSpeech) lives in DispenseTopBar and shows only on QUEUE and PRE_RX.
 */

internal const val HAZARDOUS_TAG = "HazardousFlow"

@Composable
fun DispenseFlowScreen(
    navController: NavController,
    dispenseVm: DispenseFlowViewModel = hiltViewModel(),
    dispenseCountViewModel: DispenseCountViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val loggedInRegion = LocalRegion.current
    val dispenseState by dispenseVm.uiState.collectAsState()
    val dispenseCountState by dispenseCountViewModel.uiState.collectAsState()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isTabletDevice = configuration.smallestScreenWidthDp >= 600
    val isSoundEnabled = dispenseCountState.isVoiceOverEnabled
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

    // Back to the queue list: the count drops its steps (and gloves), the flow resets to QUEUE.
    val leaveForQueue: (Boolean) -> Unit = { resetGloves ->
        dispenseCountViewModel.onAction(DispenseCountAction.LeavingForQueue(resetGloves = resetGloves))
        dispenseVm.onAction(DispenseFlowAction.LeavingForQueue)
    }

    // Listen for the dispenseCountViewModel's toasts and its "finished" (Done → Dashboard).
    // Without this collect, hitting "All Done" in the pill panel would
    // fire the confirmation dialog but the user would never actually leave the
    // screen on confirm.
    LaunchedEffect(Unit) {
        dispenseCountViewModel.effects.collect { effect ->
            when (effect) {
                is DispenseCountEffect.ShowToast -> showToast(context, loggedInRegion.wording(effect.text))
                DispenseCountEffect.Finished -> {
                    // Do NOT reset workflow steps here — that changes pillStepType
                    // while still on COUNTING stage and triggers unwanted TTS speech.
                    // Steps are reset via StageChanged(QUEUE) in onStageChanged when stage becomes QUEUE.
                    // Always check for pending dispense items after completing a transaction;
                    // show the queue if any exist, otherwise navigate to Dashboard.
                    dispenseVm.onAction(DispenseFlowAction.CountFinished)
                }
            }
        }
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
        dispenseCountViewModel.onAction(DispenseCountAction.HistoryVisibilityChanged(showHistory))
    }

    val pillStepType = dispenseCountState.currentStep
    val capturedBitmap = dispenseCountState.capturedStill
    val pillSteps = dispenseCountState.steps
    val recentDetectionCounts by dispenseCountViewModel.recentDetectionCounts.collectAsState()
    val engineState by dispenseCountViewModel.engineState.collectAsState()
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
    val awaitingResume = dispenseState.isResolvingEntry

    // Which handler a barcode read goes to, per stage and counting step.
    val barcodeRouting = remember { DispenseBarcodeRouting(ScanDecoder(BarcodeDecoder()), loggedInRegion) }
    val readsBarcodes = barcodeRouting.readsBarcodes(
        stage = dispenseState.stage,
        currentStep = pillStepType,
        isVialStillShown = capturedBitmap != null,
    )

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

    // === Pill-VM dialogs ===
    DispenseCountDialogs(
        countState = dispenseCountState,
        onCountAction = dispenseCountViewModel::onAction,
        onTargetCountCancelled = {
            if (dispenseState.isFromQueue) leaveForQueue(false) else navController.navigateBackToDashboard()
        },
    )

    engineState.pendingTrayColor?.let { pendingColor ->
        TrayClassificationDialog(
            pendingColor = pendingColor,
            isHazardousTxn = dispenseState.isHazardous,
            onCountAction = dispenseCountViewModel::onAction,
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
        }
        // The txn id only changes with the stage, or on the Rx step where nothing is saved.
        dispenseCountViewModel.onAction(
            DispenseCountAction.StageChanged(
                dispenseState.stage,
                txnId = dispenseState.txnId,
                isHazardousDrug = dispenseState.isHazardous,
            )
        )
    }

    // Pause pill detection any time a verification sheet, popup, error dialog,
    // or loading state is on top of the camera. Saves CPU/GPU while user is
    // looking at modal UI; also avoids stale detections flickering if the user
    // looks at pills while the RX sheet is visible.
    LaunchedEffect(
        dispenseState.showRxDetails,
        dispenseState.showNdcEquivalenceDialog,
        dispenseState.isLoading,
    ) {
        val anyOverlay = dispenseState.showRxDetails ||
                dispenseState.showNdcEquivalenceDialog ||
                dispenseState.isLoading
        dispenseCountViewModel.onAction(DispenseCountAction.FlowOverlayChanged(isShown = anyOverlay))
    }

    val barcodeAnalyzer = remember {
        FrameBarcodeAnalyzer(
            context.applicationContext,
            isGrayscale = dispenseCountViewModel::isGrayscaleImage,
            region = loggedInRegion,
        )
    }
    DisposableEffect(Unit) {
        onDispose { barcodeAnalyzer.pause() }
    }

    // Turned-away scans fire their toast once per scan result, but both scan inputs
    // (camera + BT) would otherwise be ready to read again the instant
    // isLoading clears — same physical label under the camera, or the same
    // BT scanner still primed — so the same result fires again almost
    // instantly and the toast looks continuous. scanCooldownActive pauses
    // BOTH inputs briefly after ANY rejection that asks for it (regardless of which one)
    // so the user has a moment before the next read — folded into the gate
    // LaunchedEffect below so a dialog opening mid-cooldown still wins.
    var scanCooldownActive by remember { mutableStateOf(false) }
    // Bumped per pausing rejection, so a second one restarts the pause.
    var scanCooldownStarts by remember { mutableIntStateOf(0) }
    LaunchedEffect(scanCooldownStarts) {
        if (scanCooldownStarts > 0) {
            scanCooldownActive = true
            delay(1500)
            scanCooldownActive = false
        }
    }

    // Pause/resume the barcode analyzer to match the stage. Outside the
    // pre-stages the barcode scanner sits idle; while ANY verification sheet,
    // error dialog, or loading indicator is on top of the camera we don't want
    // to keep firing the analyzer either — otherwise we burn CPU on reads we'd
    // immediately ignore, AND we risk re-triggering the same barcode the moment
    // the dialog closes.
    val isBarcodeScanOpen = readsBarcodes &&
            !dispenseState.showRxDetails &&
            !dispenseState.showNdcEquivalenceDialog &&
            !dispenseState.isLoading &&
            !scanCooldownActive
    LaunchedEffect(
        dispenseState.stage,
        pillStepType,
        capturedBitmap != null,
        dispenseState.showRxDetails,
        dispenseState.showNdcEquivalenceDialog,
        dispenseState.isLoading,
        scanCooldownActive,
    ) {
        // Pre-stages scan for the RX / NDC labels; the VIAL step scans the vial
        // label to auto-capture when its RX matches the active transaction; the
        // counting steps scan for a same-NDC bottle rescan mid-count.
        if (isBarcodeScanOpen) barcodeAnalyzer.resume() else barcodeAnalyzer.pause()
    }

    // The flow view model's toasts, its vial match and its "leave". This collector
    // is launched once, so it reads the gate through rememberUpdatedState.
    val currentIsBarcodeScanOpen by rememberUpdatedState(isBarcodeScanOpen)
    LaunchedEffect(Unit) {
        dispenseVm.effects.collect { effect ->
            when (effect) {
                is DispenseFlowEffect.ScanRejected -> {
                    effect.toastText?.let { toastText -> showToast(context, loggedInRegion.wording(toastText)) }
                    // Read again after the pause, or now when nothing is in the way.
                    if (effect.pauseScanning) {
                        scanCooldownStarts++
                    } else if (currentIsBarcodeScanOpen) {
                        barcodeAnalyzer.resume()
                    }
                }
                DispenseFlowEffect.GenericError -> showToast(context, R.string.error_generic)
                DispenseFlowEffect.VialLabelMatched -> {
                    // Auto-capture: always grab the still on a match.
                    // Only auto-commit (as if Done was tapped) when
                    // VIAL is the LAST workflow step — then the
                    // transaction completes without a manual tap. When
                    // VIAL is followed by more steps, just show the
                    // captured image and wait for the user to tap Done
                    // before advancing. Manual capture (camera button)
                    // always requires a Done tap.
                    dispenseCountViewModel.onAction(DispenseCountAction.VialLabelMatched)
                }
                DispenseFlowEffect.LeaveToDashboard -> navController.navigateBackToDashboard()
            }
        }
    }

    // Track in-flight pill count from the detector (only meaningful in COUNTING).
    var filteredPillCount by remember { mutableStateOf(0) }

    // One snapshot for the count panel, the camera overlay and View All.
    val countPanelState = CountPanelState(
        drugName = dispenseCountState.drugName,
        ndc = dispenseCountState.ndc,
        strength = dispenseCountState.strength,
        bucket = if (LocalRegion.current.showsBucket) dispenseCountState.bucketId ?: stringResource(R.string.default_bucket) else "",
        dosageForm = dispenseCountState.dosageForm,
        drugImage = dispenseCountState.drugImage,
        totalCount = dispenseCountState.totalCount,
        targetCount = dispenseCountState.targetCount,
        isOpenCount = false,
        stepType = pillStepType,
        steps = pillSteps,
        isVoiceOverEnabled = isSoundEnabled,
        detectedCount = filteredPillCount,
        recentDetectionCounts = recentDetectionCounts,
        isAddCooldown = engineState.isAddCooldown,
        isIdle = engineState.showIdleOverlay,
        showGloveIcon = sessionHazardous,
        glovesDetected = engineState.glovesDetected,
        hasStill = capturedBitmap != null,
        isCapturing = dispenseCountState.isCapturing,
    )

    // ── Back handling ───────────────────────────────────────────────────────
    // The priority order is DispenseBackPress.onDeviceBack's.
    BackHandler {
        val backPressOutcome = DispenseBackPress.onDeviceBack(
            isHistoryShown = showHistory,
            isIdleOverlayShown = engineState.showIdleOverlay,
            stage = dispenseState.stage,
            isFromQueue = dispenseState.isFromQueue,
        )
        when (backPressOutcome) {
            BackPressOutcome.CloseHistory -> showHistory = false
            BackPressOutcome.ResumeFromIdle ->
                dispenseCountViewModel.onAction(DispenseCountAction.ResumeFromIdleTapped)
            BackPressOutcome.LeaveToDashboard -> navController.navigateBackToDashboard()
            BackPressOutcome.ReturnToQueue -> leaveForQueue(true)
            BackPressOutcome.PopBack -> {
                // Pop back to whichever screen launched this flow (Dashboard for a
                // direct-dispense entry). Fall back to Dashboard only if the back-stack is empty.
                val popped = navController.popBackStack()
                if (!popped) {
                    navController.navigateBackToDashboard()
                }
            }
        }
    }

    DispenseFlowDialogs(flowState = dispenseState, onFlowAction = dispenseVm::onAction)

    // Re-focus the BT scanner field whenever all overlays dismiss so the next
    // scan is captured without the user tapping the field.
    val btScannerOverlayActive = dispenseState.showRxDetails ||
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
        ResetCountDialog(
            onCancel = { showResetDialog = false },
            onConfirm = {
                showResetDialog = false
                // One call on the view model's scope. Disposing the screen mid-reset
                // must not leave a wiped transaction with the stage on COUNTING.
                dispenseVm.onAction(
                    DispenseFlowAction.ResetConfirmed {
                        // Delete first, then move the stage — otherwise the SCAN strip
                        // renders against counts that are still on their way out.
                        // Land on SCAN before the stage flips. The stage effect would do
                        // it a frame later, by which time the strip has already composed
                        // against the step we just left and spent its announcement on it.
                        // The effect repeating the call after the flip is harmless.
                        // A failed reset keeps the current step (the VM shows the error).
                        dispenseCountViewModel.resetForRecount().also { ok ->
                            // The analyzer self-pauses after every read; re-arm it for the rescan.
                            if (ok) barcodeAnalyzer.resume()
                        }
                    }
                )
            },
        )
    }

    // A read goes to whichever view model its stage and step route it to.
    // Conditions are read live here (not via the precomputed
    // readsBarcodes) because this lambda is captured once
    // by CameraFeed's frame collector — a plain
    // captured Boolean would be frozen at first composition.
    val onBarcodeRead: (String, String?) -> Unit = { rawValue, imagePath ->
        // No real scans during the tour: the demo data is fed in by the tour card's Next.
        val barcodeRoute = if (FtueController.state.isActive) BarcodeRoute.Ignored else barcodeRouting.route(
            rawValue = rawValue,
            imagePath = imagePath,
            stage = dispenseState.stage,
            currentStep = dispenseCountState.currentStep,
            isVialStillShown = dispenseCountState.capturedStill != null,
        )
        when (barcodeRoute) {
            is BarcodeRoute.RxLabel ->
                dispenseVm.onAction(DispenseFlowAction.RxLabelScanned(barcodeRoute.rawValue))
            is BarcodeRoute.Container ->
                dispenseVm.onAction(DispenseFlowAction.ContainerScanned(barcodeRoute.scannedCode, barcodeRoute.firstBottle))
            // Wrong label for this step: the view model toasts; read again.
            BarcodeRoute.RxLabelOnContainerStep -> {
                dispenseVm.onAction(DispenseFlowAction.RxLabelScannedOnContainerStep)
                barcodeAnalyzer.resume()
            }
            is BarcodeRoute.Bottle -> {
                // The analyzer
                // self-pauses on each hit; always resume immediately afterward since
                // this hook never itself surfaces a full-screen sheet that would
                // otherwise re-trigger on the same barcode.
                dispenseCountViewModel.onAction(DispenseCountAction.BottleScanned(barcodeRoute.rawValue, barcodeRoute.imagePath))
                barcodeAnalyzer.resume()
            }
            // VIAL step: read the vial label and auto-capture the
            // photo when its RX matches the active transaction. The
            // analyzer self-pauses on each hit; on a match we leave it
            // paused (capturedBitmap will hide the live view), on a
            // miss we resume to keep scanning. Manual capture still
            // works via the camera button.
            is BarcodeRoute.VialLabel ->
                dispenseVm.onAction(DispenseFlowAction.VialLabelScanned(barcodeRoute.rawValue))
            // The analyzer self-pauses on every MLKit hit. If we
            // dropped the read (false positive, wrong format, or
            // the COUNTING stage no-op) without surfacing a sheet,
            // resume immediately — otherwise barcode scanning
            // would be dead until the user dismissed something
            // that never appeared.
            BarcodeRoute.Ignored -> barcodeAnalyzer.resume()
        }
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
                        dispenseCountViewModel.onAction(DispenseCountAction.ScreenTouched)
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
                    isCameraPaused = engineState.isCameraPaused,
                    isStillShown = capturedBitmap != null,
                    onFrame = { imageProxy ->
                        // Only the barcode analyzer reads the frame metadata before the
                        // frame is forwarded to the pill VM (which always closes it). When
                        // pill detection is paused, the VM still closes the frame on the
                        // pause-fast-path inside onFrameCaptured — no leak.
                        val readsBarcodesNow = barcodeRouting.readsBarcodes(
                            stage = dispenseState.stage,
                            currentStep = dispenseCountState.currentStep,
                            isVialStillShown = dispenseCountState.capturedStill != null,
                        )
                        if (readsBarcodesNow) barcodeAnalyzer.analyze(imageProxy, onBarcodeRead)
                        dispenseCountViewModel.onAction(DispenseCountAction.FrameArrived(imageProxy))
                    },
                    onCameraHelperReady = { cameraHelper ->
                        dispenseCountViewModel.onAction(DispenseCountAction.CameraHelperReady(cameraHelper))
                    },
                    onCameraReady = {
                        dispenseCountViewModel.onAction(DispenseCountAction.PreviewSizeKnown)
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
                    showExcessPills = dispenseCountState.isTxnLoaded,
                    capturedStill = capturedBitmap,
                    showFlash = dispenseCountState.showFlash,
                    addPops = dispenseCountViewModel.addPops,
                    onFilteredPills = { filteredPills ->
                        dispenseCountViewModel.onAction(DispenseCountAction.FilteredPillsDrawn(filteredPills))
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
                    CountingStage(
                        countPanelState = countPanelState,
                        canReset = dispenseCountState.canReset,
                        onCountAction = dispenseCountViewModel::onAction,
                        onAddTapped = {
                            dispenseCountViewModel.onAction(DispenseCountAction.AddTapped(filteredPillCount))
                        },
                        onShowHistory = { showHistory = true },
                        onResetTapped = { showResetDialog = true },
                    )
                } else {
                    // PRE_RX / PRE_NDC — show only the live count circle. No
                    // Add/Done/total: the user can't commit a count until RX +
                    // NDC are scanned, and the voice prompt guides them there.
                    // In portrait the circle clears the SCAN step's stepper, which
                    // sits along the bottom edge.
                    LiveCountCircle(
                        detectedCount = filteredPillCount,
                        pillsInView = filteredPillCount > 0 || engineState.detectedPills.isNotEmpty(),
                        recentDetectionCounts = recentDetectionCounts,
                        isAddCooldown = engineState.isAddCooldown,
                        isIdle = engineState.showIdleOverlay,
                        onCountSteady = { dispenseCountViewModel.onAction(DispenseCountAction.CountSteadied) },
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
        if (dispenseState.stage == DispenseStage.PRE_NDC && !showHistory && !awaitingResume) {
            ContainerScanStage(
                flowState = dispenseState,
                showDetailsBar = showScanDetailsBar,
                steps = pillSteps,
                currentStep = pillStepType,
                isVoiceOverEnabled = isSoundEnabled,
                isTablet = isTabletDevice,
                isLandscape = isLandscape,
                autoRevealCurrentStep = announcedScanStep != pillStepType,
                onAutoRevealed = { announcedScanStep = pillStepType },
                onStripHeightKnown = { stripHeight -> scanStripHeight = stripHeight },
            )
        }

        // ── QUEUE stage overlay ───────────────────────────────────────────────
        if (dispenseState.stage == DispenseStage.QUEUE && !showHistory) {
            QueueStage(
                queueItems = dispenseState.queueItems,
                selectedFilter = dispenseState.selectedQueueFilter,
                isLandscape = isLandscape,
                isTablet = isTabletDevice,
                onFilterSelected = { filter -> dispenseVm.onAction(DispenseFlowAction.QueueFilterSelected(filter)) },
                onItemTapped = { txnId -> dispenseVm.onAction(DispenseFlowAction.QueueItemTapped(txnId)) },
                onHomeTapped = { navController.navigateBackToDashboard() },
            )
        }

        // History list takes over the screen when the user taps Total Count.
        if (showHistory) {
            CountHistory(
                navController = navController,
                isLandscape = isLandscape,
                countPanelState = countPanelState,
                stepCounts = dispenseCountState.stepCounts,
                selectedImagePath = fullScreenImagePath,
                onCountDeleted = { countId ->
                    dispenseCountViewModel.onAction(DispenseCountAction.CountDeleted(countId))
                },
                onBack = { showHistory = false },
                onImageSelected = { imagePath -> fullScreenImagePath = imagePath },
            )
        }

        // Idle overlay — when the pill VM pauses itself due to inactivity, fade
        // in a "Counting paused" prompt with a Resume button. Matches the
        // legacy screen.
        if (engineState.showIdleOverlay && !showHistory) {
            CountingPausedOverlay(onResume = { dispenseCountViewModel.onAction(DispenseCountAction.ResumeFromIdleTapped) })
        }

        if (!showHistory) {
            DispenseTopBar(
                navController = navController,
                stage = dispenseState.stage,
                currentStep = pillStepType,
                showScanDetailsBar = showScanDetailsBar,
                awaitingResume = awaitingResume,
                isLandscape = isLandscape,
                isTablet = isTabletDevice,
                isSoundEnabled = isSoundEnabled,
                onBackArrow = {
                    val backArrowOutcome = DispenseBackPress.onBackArrow(dispenseState.stage, dispenseState.isFromQueue)
                    if (backArrowOutcome == BackPressOutcome.ReturnToQueue) {
                        leaveForQueue(true)
                    } else {
                        navController.navigateBackToDashboard()
                    }
                },
            )
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
                    if (barcode.isNotBlank()) onBarcodeRead(barcode, null)
                },
                focusRequester = btFocusRequester,
                modifier = Modifier.align(Alignment.TopStart),
            )
        }

        if (dispenseState.isLoading) {
            BreathingDotsLoader(modifier = Modifier.align(Alignment.Center))
        }

        if (dispenseState.showRxDetails) {
            RxScanStage(
                flowState = dispenseState,
                isLandscape = isLandscape,
                isTablet = isTabletDevice,
                onFlowAction = dispenseVm::onAction,
            )
        }

        // Loading gate for resume/HL7 entries — drawn last so it sits on top
        // until the real start stage is resolved. Rather than an opaque white
        // cover (which reads as a frozen/blank screen), we keep the live camera
        // visible underneath and lay a light dark scrim + loader over it, so it
        // looks like the session is loading over the camera. The wrong-stage
        // PRE_RX overlays are suppressed separately (see the awaitingResume checks /
        // BtScannerInputBar) so nothing incorrect bleeds through the scrim.
        GlobalLoadingOverlay(
            isVisible = awaitingResume,
            backgroundColor = Color.Black.copy(alpha = 0.4f),
        )

        // Skip the screen-root overlay while the Rx verify sheet is up: in landscape,
        // VerifyRxDetailsInlinePanel renders inline in this same window with its own
        // TourGuideOverlay, and both would otherwise render duplicate scrims/tooltips
        // for the same "verify_rx_proceed" target.
        // d_note is drawn by the Add Note dialog's own window (TourDialogHost).
        if (!dispenseState.showRxDetails && ftueState.currentStep?.id != "d_note") {
            val tourDemoDrugName = stringResource(R.string.tour_demo_drug_name)
            TourGuideOverlay(
                state = ftueState,
                modifier = Modifier.fillMaxSize(),
                onNext = {
                    when (ftueState.currentStep?.id) {
                        "d_scan_rx" -> {
                            // The demo Rx is ignored mid-load or in another stage; wait for a state it accepts.
                            withTimeout(TourActionTimeoutMs) {
                                dispenseVm.uiState.first {
                                    it.showRxDetails || (!it.isLoading && (it.stage == DispenseStage.PRE_RX || it.stage == DispenseStage.QUEUE))
                                }
                            }
                            if (!dispenseVm.uiState.value.showRxDetails) {
                                dispenseVm.onAction(DispenseFlowAction.TourRxNextTapped(tourDemoDrugName))
                            }
                            withTimeout(TourActionTimeoutMs) { dispenseVm.uiState.first { it.showRxDetails } }
                        }
                        "d_scan_container" -> {
                            // The Rx sheet's Proceed creates the txn asynchronously.
                            // A retry may find the previous attempt already past PRE_NDC.
                            if (dispenseVm.uiState.value.stage != DispenseStage.COUNTING) {
                                withTimeout(TourActionTimeoutMs) { dispenseVm.uiState.first { it.stage == DispenseStage.PRE_NDC } }
                                dispenseVm.onAction(DispenseFlowAction.TourContainerNextTapped)
                            }
                            withTimeout(TourActionTimeoutMs) { dispenseVm.uiState.first { it.stage == DispenseStage.COUNTING } }
                            // The count circle only exists once the workflow is on a counting step;
                            // without this the next step would spotlight nothing.
                            withTimeout(TourActionTimeoutMs) { dispenseCountViewModel.uiState.first { it.currentStep in countingSteps } }
                        }
                        "d_count" -> {
                            dispenseCountViewModel.dummyCompleteCount()
                            withTimeout(TourActionTimeoutMs) {
                                dispenseCountViewModel.uiState.first { it.currentStep == StepState.VIAL || it.currentStep !in countingSteps }
                            }
                            // The vial photo isn't a tour step; save a demo one so the count can finish.
                            if (dispenseCountViewModel.uiState.value.currentStep == StepState.VIAL) dispenseCountViewModel.captureVialForTour()
                        }
                    }
                },
            )
        }
    }
}

private val countingSteps = setOf(StepState.CONTAINER_INITIATE, StepState.TARGET_VERIFICATION, StepState.TARGET_REVERIFICATION)
