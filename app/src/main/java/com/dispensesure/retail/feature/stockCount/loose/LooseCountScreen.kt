package com.dispensesure.retail.feature.stockCount.loose

import android.Manifest
import android.content.Context
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.dispensesure.retail.R
import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.region.LocalRegion
import com.dispensesure.retail.core.scanning.analyzer.FrameBarcodeAnalyzer
import com.dispensesure.retail.core.scanning.domain.model.TxnDetail
import com.dispensesure.retail.core.scanning.presentation.compose.BtScannerInputBar
import com.dispensesure.retail.core.scanning.presentation.compose.CameraFeed
import com.dispensesure.retail.core.scanning.presentation.compose.ContainerScanStrip
import com.dispensesure.retail.core.scanning.presentation.compose.CountOverlay
import com.dispensesure.retail.core.scanning.presentation.compose.CountingPausedOverlay
import com.dispensesure.retail.core.scanning.presentation.compose.LiveCountCircle
import com.dispensesure.retail.core.scanning.presentation.compose.count.CountPanelState
import com.dispensesure.retail.core.scanning.presentation.compose.count.HistoryModeLandscape
import com.dispensesure.retail.core.scanning.presentation.compose.count.HistoryModePortrait
import com.dispensesure.retail.core.scanning.presentation.compose.count.InformationPanelSection
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.BackButton
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.CommonDialog
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.showToast
import com.dispensesure.retail.core.utils.common.navigateBackToDashboard
import com.dispensesure.retail.core.utils.compose.BreathingDotsLoader
import com.dispensesure.retail.core.utils.permission.rememberPermissionStateDetailed
import com.dispensesure.retail.navigation.Screen
import kotlinx.coroutines.delay

// After a container scan is turned away with a toast, how long both scan inputs wait.
private const val SCAN_COOLDOWN_MS = 1500L

/**
 * Loose-pill count for a stock batch, opened from SCAN PILLS on the sealed-bottle screen.
 * Scan the container, ADD pills, then Proceed → Yes saves the total; back drops it.
 */
@Composable
fun LooseCountScreen(
    navController: NavController,
    viewModel: LooseCountViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val engineState by viewModel.engineState.collectAsState()
    val recentDetectionCounts by viewModel.recentDetectionCounts.collectAsState()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isTabletDevice = configuration.smallestScreenWidthDp >= 600
    val keyboardController = LocalSoftwareKeyboardController.current

    var showHistory by rememberSaveable { mutableStateOf(false) }
    // Saveable so an open photo survives the portrait/landscape View All swap.
    var fullScreenImagePath by rememberSaveable { mutableStateOf<String?>(null) }
    var showResetDialog by rememberSaveable { mutableStateOf(false) }
    // Pills the overlay counts on the tray right now; ADD adds this many.
    var filteredPillCount by remember { mutableStateOf(0) }
    var scanStripHeight by remember { mutableStateOf(0.dp) }
    var announcedScanStep by remember(uiState.stage) { mutableStateOf<StepState?>(null) }
    var scanCooldownActive by remember { mutableStateOf(false) }
    var btScannerInput by remember { mutableStateOf("") }
    val btFocusRequester = remember { FocusRequester() }

    // Back to the sealed-bottle screen; Dashboard when nothing is under this one.
    val leaveScreen: () -> Unit = {
        if (!navController.popBackStack()) navController.navigateBackToDashboard()
    }

    val cameraPermissionState = rememberPermissionStateDetailed(
        permission = Manifest.permission.CAMERA,
        rationaleTitle = stringResource(R.string.permission_camera_title),
        rationaleMessage = stringResource(R.string.permission_camera_rationale),
    )
    // Same redirect as the dispense screen: no camera, no count.
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

    val loggedInRegion = LocalRegion.current
    val barcodeAnalyzer = remember {
        FrameBarcodeAnalyzer(context.applicationContext, isGrayscale = viewModel::isGrayscaleImage, region = loggedInRegion)
    }
    DisposableEffect(Unit) { onDispose { barcodeAnalyzer.close() } }

    // Only the container scan reads barcodes. The analyzer pauses itself on every read.
    val isContainerScanOpen = uiState.stage == LooseCountStage.SCAN_CONTAINER &&
        !uiState.isResolvingContainer &&
        !scanCooldownActive
    LaunchedEffect(isContainerScanOpen) {
        if (isContainerScanOpen) barcodeAnalyzer.resume() else barcodeAnalyzer.pause()
    }
    LaunchedEffect(scanCooldownActive) {
        if (scanCooldownActive) {
            delay(SCAN_COOLDOWN_MS)
            scanCooldownActive = false
        }
    }

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is LooseCountEffect.ShowToast -> showToast(message = context.toastText(effect.text, effect.formatArg))
                is LooseCountEffect.ContainerRejected -> {
                    val toastText = effect.toastText
                    if (toastText == null) {
                        // Not a container barcode: read again straight away.
                        barcodeAnalyzer.resume()
                    } else {
                        showToast(message = context.toastText(toastText, effect.formatArg))
                        scanCooldownActive = true
                    }
                }
                is LooseCountEffect.CountSaved -> {
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set(Screen.LooseCount.RESULT_BATCH_ID, effect.batchId)
                    leaveScreen()
                }
            }
        }
    }

    LaunchedEffect(showHistory) {
        viewModel.onAction(LooseCountAction.HistoryVisibilityChanged(showHistory))
    }

    // Back closes View All, then the idle scrim, then leaves; leaving drops the count.
    // While a save runs back does nothing: leaving would delete photos the save is keeping.
    BackHandler {
        when {
            uiState.isSaving -> Unit
            showHistory -> showHistory = false
            engineState.showIdleOverlay -> viewModel.onAction(LooseCountAction.ResumeFromIdleTapped)
            else -> leaveScreen()
        }
    }

    // Focus the BT scanner field whenever it shows, so the next scan lands without a tap.
    val showBtScannerBar = !showHistory && isContainerScanOpen
    LaunchedEffect(showBtScannerBar) {
        if (showBtScannerBar) {
            btScannerInput = ""
            try {
                btFocusRequester.requestFocus()
                keyboardController?.hide()
            } catch (_: Exception) {}
        }
    }

    if (uiState.showEndCountDialog) {
        CommonDialog(
            message = stringResource(R.string.are_you_sure_you_want_to_end_this_count),
            title = stringResource(R.string.confirmation),
            confirmText = stringResource(R.string.yes),
            cancelText = stringResource(R.string.no),
            onConfirm = { viewModel.onAction(LooseCountAction.EndCountConfirmed) },
            onCancel = { viewModel.onAction(LooseCountAction.EndCountDismissed) },
        )
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
                viewModel.onAction(LooseCountAction.ResetConfirmed)
            },
        )
    }

    // A tray colour seen for the first time on a hazardous drug: ask whether it is the hazardous tray.
    engineState.pendingTrayColor?.let { pendingColor ->
        CommonDialog(
            title = stringResource(R.string.tray_classification_title),
            message = stringResource(R.string.tray_classification_message, pendingColor.label),
            confirmText = stringResource(R.string.yes),
            cancelText = stringResource(R.string.no),
            onConfirm = { viewModel.onAction(LooseCountAction.TrayClassified(pendingColor, isHazardous = true)) },
            onCancel = { viewModel.onAction(LooseCountAction.TrayClassified(pendingColor, isHazardous = false)) },
        )
    }

    val countedDrug = uiState.drug
    val countPanelState = CountPanelState(
        drugName = countedDrug?.drugName.orEmpty(),
        ndc = countedDrug?.ndc.orEmpty(),
        strength = countedDrug?.strength.orEmpty(),
        bucket = uiState.bucketId ?: stringResource(R.string.default_bucket),
        dosageForm = countedDrug?.dosageForm.orEmpty(),
        drugImage = countedDrug?.drugImagePath.orEmpty(),
        totalCount = uiState.totalCount,
        targetCount = 0,
        isOpenCount = true,
        stepType = uiState.currentStep,
        steps = LOOSE_COUNT_STEPS,
        isVoiceOverEnabled = uiState.isVoiceOverEnabled,
        detectedCount = filteredPillCount,
        recentDetectionCounts = recentDetectionCounts,
        isAddCooldown = engineState.isAddCooldown,
        isIdle = engineState.showIdleOverlay,
        showGloveIcon = uiState.stage == LooseCountStage.COUNTING && countedDrug?.isHazardous == true,
        glovesDetected = engineState.glovesDetected,
        hasStill = false,
        isCapturing = false,
    )
    val historyRows = remember(uiState.entries) {
        uiState.entries.map { entry ->
            TxnDetail(
                txnDetailId = entry.entryId,
                count = entry.pillCount,
                createdAt = entry.createdAt,
                image = entry.imagePath,
                type = StepState.TARGET_VERIFICATION,
            )
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
                        viewModel.onAction(LooseCountAction.ScreenTouched)
                    }
                }
            }
    ) {
        if (!showHistory) {
            if (cameraPermissionState.granted) {
                CameraFeed(
                    isCameraPaused = engineState.isCameraPaused,
                    isStillShown = false,
                    onFrame = { imageProxy ->
                        // uiState is read live here; CameraFeed keeps the first lambda it gets.
                        if (uiState.stage == LooseCountStage.SCAN_CONTAINER) {
                            barcodeAnalyzer.analyze(imageProxy) { rawValue, _ ->
                                viewModel.onAction(LooseCountAction.ContainerScanned(rawValue))
                            }
                        }
                        viewModel.onAction(LooseCountAction.FrameArrived(imageProxy))
                    },
                    onCameraHelperReady = { cameraHelper ->
                        viewModel.onAction(LooseCountAction.CameraHelperReady(cameraHelper))
                    },
                    onCameraReady = { viewModel.onAction(LooseCountAction.CameraReady) },
                    modifier = Modifier.fillMaxSize(),
                )
                CountOverlay(
                    engineStates = viewModel.engineState,
                    runningTotal = uiState.totalCount,
                    targetCount = 0,
                    stepType = uiState.currentStep,
                    // An open count has no target, so no pill is ever excess.
                    showExcessPills = false,
                    capturedStill = null,
                    showFlash = false,
                    addPops = viewModel.addPops,
                    onFilteredPills = { filteredPills ->
                        viewModel.onAction(LooseCountAction.FilteredPillsDrawn(filteredPills))
                        filteredPillCount = filteredPills.size
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (uiState.stage == LooseCountStage.COUNTING) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxSize()
                ) {
                    InformationPanelSection(
                        state = countPanelState,
                        onAdd = { viewModel.onAction(LooseCountAction.AddTapped(filteredPillCount)) },
                        onDone = { viewModel.onAction(LooseCountAction.DoneTapped) },
                        onShowHistory = { showHistory = true },
                        onCountSteady = { viewModel.onAction(LooseCountAction.CountSteadied) },
                        // An open count has no VIAL step.
                        onCapture = {},
                        onRedoCapture = {},
                        onSaveCapture = {},
                        onReset = if (uiState.canReset) {
                            { showResetDialog = true }
                        } else null,
                    )
                }
            } else {
                LiveCountCircle(
                    detectedCount = filteredPillCount,
                    pillsInView = filteredPillCount > 0 || engineState.detectedPills.isNotEmpty(),
                    recentDetectionCounts = recentDetectionCounts,
                    isAddCooldown = engineState.isAddCooldown,
                    isIdle = engineState.showIdleOverlay,
                    onCountSteady = { viewModel.onAction(LooseCountAction.CountSteadied) },
                    isLandscape = isLandscape,
                    bottomInset = scanStripHeight,
                )
            }
        }

        // No details bar on the container scan: the drug is only known once it is scanned.
        if (uiState.stage == LooseCountStage.SCAN_CONTAINER && !showHistory) {
            ContainerScanStrip(
                steps = LOOSE_COUNT_STEPS,
                currentStep = StepState.SCAN,
                isVoiceOverEnabled = uiState.isVoiceOverEnabled,
                isTablet = isTabletDevice,
                isLandscape = isLandscape,
                autoRevealCurrentStep = announcedScanStep != StepState.SCAN,
                onAutoRevealed = { announcedScanStep = StepState.SCAN },
                isFixed = false,
                onHeightKnown = { stripHeight -> scanStripHeight = stripHeight },
            )
        }

        if (showHistory) {
            if (isLandscape) {
                HistoryModeLandscape(
                    navController = navController,
                    showTargetCount = false,
                    targetCount = 0,
                    totalCount = uiState.totalCount,
                    txnHistory = historyRows,
                    onDeleteTxn = { entryId -> viewModel.onAction(LooseCountAction.EntryDeleted(entryId)) },
                    drugName = countedDrug?.drugName.orEmpty(),
                    onBack = { showHistory = false },
                    selectedImagePath = fullScreenImagePath,
                    onImageSelected = { imagePath -> fullScreenImagePath = imagePath },
                )
            } else {
                HistoryModePortrait(
                    navController = navController,
                    showTargetCount = false,
                    targetCount = 0,
                    totalCount = uiState.totalCount,
                    txnHistory = historyRows,
                    onDeleteTxn = { entryId -> viewModel.onAction(LooseCountAction.EntryDeleted(entryId)) },
                    drugName = countedDrug?.drugName.orEmpty(),
                    onBack = { showHistory = false },
                    selectedImagePath = fullScreenImagePath,
                    onImageSelected = { imagePath -> fullScreenImagePath = imagePath },
                )
            }
        }

        if (engineState.showIdleOverlay && !showHistory) {
            CountingPausedOverlay(onResume = { viewModel.onAction(LooseCountAction.ResumeFromIdleTapped) })
        }

        if (!showHistory) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    // Above the camera view, whose pinch-zoom would otherwise take the arrow's taps.
                    .zIndex(1f)
                    .displayCutoutPadding()
                    .padding(top = 8.dp, bottom = 8.dp),
            ) {
                BackButton(
                    showBox = false,
                    // On the count, line the arrow up with the details bar, as on dispense.
                    modifier = if (uiState.stage == LooseCountStage.COUNTING) {
                        Modifier
                            .align(Alignment.TopStart)
                            .offset(y = (-8).dp)
                    } else {
                        Modifier.align(Alignment.CenterStart)
                    },
                    onClick = { if (!uiState.isSaving) leaveScreen() },
                )
            }
        }

        if (showBtScannerBar) {
            BtScannerInputBar(
                input = btScannerInput,
                onInputChange = { typedInput -> btScannerInput = typedInput },
                onSubmit = { barcode ->
                    if (barcode.isNotBlank()) viewModel.onAction(LooseCountAction.ContainerScanned(barcode))
                },
                focusRequester = btFocusRequester,
                modifier = Modifier.align(Alignment.TopStart),
            )
        }

        if (uiState.isResolvingContainer) {
            BreathingDotsLoader(modifier = Modifier.align(Alignment.Center))
        }
    }
}

// A toast's text, with its argument when it has one.
private fun Context.toastText(@StringRes text: Int, formatArg: String?): String =
    if (formatArg != null) getString(text, formatArg) else getString(text)
