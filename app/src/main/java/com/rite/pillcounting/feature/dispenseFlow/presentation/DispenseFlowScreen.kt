package com.rite.pillcounting.feature.dispenseFlow.presentation

import Screen
import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.rite.pillcounting.R
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.scanning.analyzer.FrameBarcodeAnalyzer
import com.rite.pillcounting.core.scanning.domain.data.PillScanningEvent
import com.rite.pillcounting.core.scanning.presentation.compose.AddNoteDialog
import com.rite.pillcounting.core.scanning.presentation.compose.CameraPreviewSection
import com.rite.pillcounting.core.scanning.presentation.viewmodel.PillScanningViewModel
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
import com.rite.pillcounting.feature.dispenseFlow.domain.model.DispenseStage
import com.rite.pillcounting.feature.dispenseFlow.presentation.compose.BtScannerInputBar
import com.rite.pillcounting.feature.dispenseFlow.presentation.compose.DispenseQueuePanel
import com.rite.pillcounting.feature.dispenseFlow.presentation.compose.HistoryModeLandscape
import com.rite.pillcounting.feature.dispenseFlow.presentation.compose.HistoryModePortrait
import com.rite.pillcounting.feature.dispenseFlow.presentation.compose.InformationPanelSection
import com.rite.pillcounting.feature.dispenseFlow.presentation.compose.StepTitleWithSpeech
import com.rite.pillcounting.feature.dispenseFlow.presentation.compose.TargetPillsCountDialog
import com.rite.pillcounting.feature.dispenseFlow.presentation.viewmodel.DispenseFlowViewModel
import com.rite.pillcounting.ui.theme.AppTheme
import com.rite.pillcounting.ui.theme.AppTheme.dimens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import java.util.Locale
import com.rite.pillcounting.core.scanning.domain.data.NavigationEvent as PillNavigationEvent

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
 * component (StepTitleWithSpeech) is only rendered during COUNTING.
 */

// Grace period before hiding the count circle after detections drop to 0,
// so momentary empty frames don't flicker the circle off and on.
private const val COUNT_CIRCLE_HIDE_GRACE_MS = 700L

private const val HAZARDOUS_TAG = "HazardousFlow"

@Composable
fun DispenseFlowScreen(
    navController: NavController,
    countType: String,
    fromHl7: Boolean = false,
    fromResume: Boolean = false,
    batchId: Long = 0L,
    fromQueue: Boolean = false,
    // Comma-separated NDC allowlist from a PMS batch Scan-Pills hand-off.
    // Empty string = no restriction (plain dispense / non-PMS batch).
    allowedNdcs: String = "",
    dispenseVm: DispenseFlowViewModel = hiltViewModel(),
    pillVm: PillScanningViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val dispenseState by dispenseVm.uiState.collectAsState()
    val pillState by pillVm.uiState.collectAsState()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isTabletDevice = configuration.smallestScreenWidthDp >= 600
    val isSoundEnabled by pillVm.isSoundEnabled.collectAsState()
    val keyboardController = LocalSoftwareKeyboardController.current

    // ── BT scanner input ────────────────────────────────────────────────────
    // Tracks the text typed by a Bluetooth HID barcode scanner into the overlay
    // text field. Cleared after each submission so the field is ready for the
    // next scan.
    var btScannerInput by remember { mutableStateOf("") }
    val btFocusRequester = remember { FocusRequester() }

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

    LaunchedEffect(allowedNdcs) {
        val ndcSet = allowedNdcs
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
        dispenseVm.setAllowedNdcs(ndcSet)
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
                    pillVm.discardStagedCount()
                    pillVm.resetGloveDetection()
                    // Do NOT reset workflow steps here — that changes pillStepType
                    // while still on COUNTING stage and triggers unwanted TTS speech.
                    // Steps are reset via LaunchedEffect(stage) when stage becomes QUEUE.
                    // Always check for pending dispense items after completing a transaction;
                    // show the queue if any exist, otherwise navigate to Dashboard.
                    dispenseVm.resetToQueueOrNavigateDashboard()
                }

                is PillNavigationEvent.NavigateToBatch -> {
                    // Pop back to the inventory/batch screen that launched this flow
                    // (the Batch Stock Count screen for the "Scan Pills" hand-off).
                    // If nothing can be popped (root entry), fall back to Dashboard.
                    val popped = navController.popBackStack()
                    if (!popped) {
                        navController.navigate(Screen.Dashboard.route) {
                            popUpTo(0)
                            launchSingleTop = true
                        }
                    }
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
    // InventoryFlowScreen used. Tapping the Total Count on the pill panel sets
    // this to true.
    var showHistory by rememberSaveable { mutableStateOf(false) }
    // Open-fullscreen image path for the history view. Hoisted to screen level
    // (and saveable) so it survives the portrait↔landscape HistoryMode swap on
    // rotation — otherwise rotating while a preview is open would close it and
    // drop the user back to the history grid.
    var fullScreenImagePath by rememberSaveable { mutableStateOf<String?>(null) }
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
                if (fromQueue) {
                    pillVm.resetWorkflowSteps()
                    dispenseVm.resetToQueue()
                } else {
                    navController.navigate(Screen.Dashboard.route) {
                        popUpTo(Screen.Dashboard.route) { inclusive = true }
                    }
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
            countType == CountType.FIXED.toString() && pillStepType.equals(StepState.CONTAINER_PENDING) &&
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
        Log.i(HAZARDOUS_TAG, "Tray classification popup shown | color=${pendingColor.label} | isHazardousTxn=${dispenseState.isHazardous}")
        CommonDialog(
            title = stringResource(R.string.tray_classification_title),
            message = stringResource(R.string.tray_classification_message, pendingColor.label),
            confirmText = stringResource(R.string.yes),
            cancelText = stringResource(R.string.no),
            onConfirm = {
                Log.i(HAZARDOUS_TAG, "Tray classification: user confirmed ${pendingColor.label} as HAZARDOUS → saving")
                pillVm.classifyTrayColor(pendingColor, isHazardous = true)
            },
            onCancel = {
                Log.i(HAZARDOUS_TAG, "Tray classification: user rejected ${pendingColor.label} as hazardous → saving false")
                pillVm.classifyTrayColor(pendingColor, isHazardous = false)
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
            Log.i(HAZARDOUS_TAG, "Stage → COUNTING | countType=$countType | txnId=${dispenseState.txnId} | drug=${dispenseState.drugName} | isHazardous=${dispenseState.isHazardous}")
            pillVm.resumePillDetection()
            // For stock count (REGULAR) the NDC scan already happened in PRE_NDC,
            // so skip the SCAN workflow step and start at pill counting directly.
            pillVm.getDrugInfo(
                forceStartStep = if (countType == CountType.REGULAR.toString()) StepState.TARGET_VERIFICATION else null
            )
            pillVm.showTxnInfo(countType)
            // Enable tray color detection for all transactions (hazardous and non-hazardous).
            Log.i(HAZARDOUS_TAG, "Calling setHazardousTransaction(isHazardous=${dispenseState.isHazardous})")
            pillVm.setHazardousTransaction(dispenseState.isHazardous)
            // Load glove model only now that RX + NDC are confirmed and drug is hazardous.
            if (dispenseState.isHazardous) {
                Log.i(HAZARDOUS_TAG, "Hazardous drug — loading glove model")
                pillVm.loadGloveModelAndRebuildAnalyzer()
            }
        } else {
            // PRE_RX / PRE_NDC / QUEUE — run the detector so the voice prompt can react
            // to pills-in-frame, but keep CPU/GPU off when an overlay is active.
            pillVm.resumePillDetection()
            if (dispenseState.stage == DispenseStage.QUEUE) {
                // Clear workflow step icons when returning to queue so COUNTING steps
                // don't linger. Done here (not in the NavigateToDashboard handler) to
                // avoid changing pillStepType while still on COUNTING stage, which
                // would trigger unwanted TTS speech.
                pillVm.resetWorkflowSteps()
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

    // NDC not in PMS allowlist — show the correct toast and explicitly resume
    // the barcode analyzer. Without the explicit resume here the analyzer stays
    // self-paused (from the MLKit hit) because the isLoading true→false
    // transition can be batched away by Compose and the generic LaunchedEffect
    // below never fires the resume.
    val ndcNotAllowedToastText = stringResource(
        R.string.batch_stock_count_ndc_not_in_request,
        dispenseState.ndcNotAllowedValue,
    )
    LaunchedEffect(dispenseState.ndcNotAllowedToastTick) {
        if (dispenseState.ndcNotAllowedToastTick > 0) {
            showToast(context, ndcNotAllowedToastText, Toast.LENGTH_SHORT)
            barcodeAnalyzer.resume()
        }
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
        val shouldRun = (dispenseState.stage == DispenseStage.QUEUE ||
                dispenseState.stage == DispenseStage.PRE_RX ||
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

    // ── Back handling ───────────────────────────────────────────────────────
    // Device-back priority order:
    //  1. If the history view is open, dismiss it (return to camera + pill panel).
    //  2. In QUEUE stage: back navigates to Dashboard (queue is the home for dispense).
    //  3. In non-QUEUE stage with fromQueue=true: cancel current scan, return to QUEUE.
    //  4. Otherwise: exit the dispense flow to the Dashboard.
    BackHandler {
        if (showHistory) {
            showHistory = false
        } else if (dispenseState.stage == DispenseStage.QUEUE) {
            navController.navigate(Screen.Dashboard.route) {
                popUpTo(0)
                launchSingleTop = true
            }
        } else if (fromQueue && dispenseState.stage == DispenseStage.PRE_RX) {
            // Cancel RX scan before any transaction is created → return to queue list.
            pillVm.discardStagedCount()
            pillVm.resetGloveDetection()
            pillVm.resetWorkflowSteps()
            dispenseVm.resetToQueue()
        } else {
            pillVm.discardStagedCount()
            if (batchId > 0L) {
                val popped = navController.popBackStack()
                if (!popped) {
                    navController.navigate(Screen.Dashboard.route) {
                        popUpTo(0)
                        launchSingleTop = true
                    }
                }
            } else {
                navController.navigate(Screen.Dashboard.route) {
                    popUpTo(0)
                    launchSingleTop = true
                }
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

    LaunchedEffect(dispenseState.navigateToDashboard) {
        if (dispenseState.navigateToDashboard) {
            dispenseVm.clearNavigateToDashboard()
            navController.navigate(Screen.Dashboard.route) {
                popUpTo(0)
                launchSingleTop = true
            }
        }
    }

    val invalidScanToastText = stringResource(R.string.scan_correct_label)
    LaunchedEffect(dispenseState.showInvalidScanDialog) {
        if (dispenseState.showInvalidScanDialog) {
            showToast(context, invalidScanToastText, Toast.LENGTH_SHORT)
            dispenseVm.dismissInvalidScanDialog()
        }
    }

    val ndcNotFoundToastText = stringResource(R.string.the_scanned_ndc_does_not_match)
    LaunchedEffect(dispenseState.showNdcNotFoundDialog) {
        if (dispenseState.showNdcNotFoundDialog) {
            showToast(context, ndcNotFoundToastText, Toast.LENGTH_SHORT)
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
                    pillVm.resetWorkflowSteps()
                    dispenseVm.resetToQueue()
                } else {
                    navController.navigate(Screen.Dashboard.route) {
                        popUpTo(0)
                        launchSingleTop = true
                    }
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

    val rxScannedInStockCountToastText = stringResource(R.string.scan_correct_label)
    LaunchedEffect(dispenseState.showRxScannedInStockCountDialog) {
        if (dispenseState.showRxScannedInStockCountDialog) {
            showToast(context, rxScannedInStockCountToastText, Toast.LENGTH_SHORT)
            dispenseVm.dismissRxScannedInStockCountDialog()
        }
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

    val txnNotFoundToastText = stringResource(R.string.transaction_not_found_toast)
    LaunchedEffect(dispenseState.txnNotFoundToastTick) {
        if (dispenseState.txnNotFoundToastTick > 0) {
            showToast(context, txnNotFoundToastText, Toast.LENGTH_SHORT)
        }
    }

    // Re-focus the BT scanner field whenever all overlays dismiss so the next
    // scan is captured without the user tapping the field.
    val btScannerOverlayActive = dispenseState.showRxDetails ||
            dispenseState.showNdcDetails ||
            dispenseState.showNdcNotFoundDialog ||
            dispenseState.showInvalidScanDialog ||
            dispenseState.showNdcEquivalenceDialog ||
            dispenseState.showRxScannedInStockCountDialog ||
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

    Box(modifier = Modifier.fillMaxSize()) {
        // Camera + pill panel are hidden while the history view is up — matches
        // the legacy InventoryFlowScreen behavior so CameraX doesn't rebind on
        // rotation-driven lifecycle restarts behind the history list.
        if (!showHistory) {
            if (hasCameraPermission) {
                CameraPreviewSection(
                    viewModel = pillVm,
                    pills = pillState.detectedPills,
                    isCameraPaused = pillVm.cameraPaused.collectAsState().value,
                    showGloveIcon = sessionHazardous,
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
            val showPillPanel = dispenseState.stage == DispenseStage.COUNTING || showCountCircle
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
                        onHomeClick = {
                            navController.navigate(Screen.Dashboard.route) {
                                popUpTo(0); launchSingleTop = true
                            }
                        },
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
                        onHomeClick = {
                            navController.navigate(Screen.Dashboard.route) {
                                popUpTo(0); launchSingleTop = true
                            }
                        },
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
                        onHomeClick = {
                            navController.navigate(Screen.Dashboard.route) {
                                popUpTo(0); launchSingleTop = true
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
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
                    selectedImagePath = fullScreenImagePath,
                    onImageSelected = { fullScreenImagePath = it },
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
                    selectedImagePath = fullScreenImagePath,
                    onImageSelected = { fullScreenImagePath = it },
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
                    .background(Color.Black.copy(alpha = 0.5f)),
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
                    .padding(top = 8.dp, bottom = 8.dp, end = headerEndPadding),
            ) {
                BackButton(
                    navController = navController,
                    showBox = false,
                    modifier = Modifier.align(Alignment.CenterStart),
                    onClick = {
                        pillVm.discardStagedCount()
                        if (fromQueue && dispenseState.stage == DispenseStage.PRE_RX) {
                            pillVm.resetGloveDetection()
                            pillVm.resetWorkflowSteps()
                            dispenseVm.resetToQueue()
                        } else if (batchId > 0L) {
                            val popped = navController.popBackStack()
                            if (!popped) {
                                navController.navigate(Screen.Dashboard.route) {
                                    popUpTo(0)
                                    launchSingleTop = true
                                }
                            }
                        } else {
                            navController.navigate(Screen.Dashboard.route) {
                                popUpTo(0)
                                launchSingleTop = true
                            }
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
                    StepTitleWithSpeech(
                        stepType = headerStepType,
                        isSoundOverride = isSoundEnabled,
                        titleResOverride = if (dispenseState.stage == DispenseStage.COUNTING && countType == CountType.REGULAR.toString()) R.string.scan_open_pills else null,
                    )
                }
            }
        }

        // BT scanner input bar — visible during QUEUE, PRE_RX and PRE_NDC while no modal
        // overlay is active.
        if (!showHistory &&
            dispenseState.stage != DispenseStage.COUNTING &&
            !btScannerOverlayActive
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
                            countType = countType,
                            onRx = dispenseVm::onRxBarcodeRead,
                            onNdc = dispenseVm::onNdcBarcodeRead,
                            onRxInNdcStage = dispenseVm::onRxScannedInNdcStage,
                            onRxInStockCount = dispenseVm::onRxScannedInStockCount,
                        )
                        if (!dispatched) barcodeAnalyzer.resume()
                    }
                },
                focusRequester = btFocusRequester,
                modifier = Modifier.align(Alignment.TopStart),
            )
        }

        if (dispenseState.isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
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
internal fun handleBarcode(
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
        DispenseStage.QUEUE,
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

