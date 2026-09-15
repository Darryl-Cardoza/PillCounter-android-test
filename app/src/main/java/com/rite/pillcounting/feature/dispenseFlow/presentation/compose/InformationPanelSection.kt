package com.rite.pillcounting.feature.dispenseFlow.presentation.compose

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.scanning.domain.data.PillScanningEvent
import com.rite.pillcounting.core.scanning.domain.model.PillScanningUiState
import com.rite.pillcounting.core.scanning.presentation.viewmodel.PillScanningViewModel
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils

@Composable
fun InformationPanelSection(
    uiState: PillScanningUiState,
    viewModel: PillScanningViewModel,
    onEvent: (PillScanningEvent) -> Unit,
    filteredPillCount: Int,
    onShowHistory: () -> Unit,
    showGloveIcon: Boolean = false,
    onReset: (() -> Unit)? = null,
) {
    val stepType by viewModel.currentStep.collectAsState()
    val glovesDetected by viewModel.glovesDetected.collectAsState()
    val totalCount = if (uiState.scanType == CountType.REGULAR.toString()) {
        uiState.stockCountSessionTotal
    } else {
        uiState.txnDetailHistory.sumOf { it.count }
    }
    val drugName = uiState.drugName
    val targetCount = uiState.targetCount
    val scanType = uiState.scanType
    val onAdd = { onEvent(PillScanningEvent.AddTransactionDetailClicked(filteredPillCount, stepType)) }
    val onDone = { onEvent(PillScanningEvent.FinalDone(stepType = stepType, totalCount)) }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Rotation swaps the CountMode variant below and recreates its
    // WorkflowStepper, so the announced step is remembered here instead.
    var announcedStep by remember { mutableStateOf<StepState?>(null) }

    val isTablet = UserInterfaceUtils.isTablet()
    val autoRevealCurrentStep = stepType != announcedStep
    val onAutoRevealed: () -> Unit = { announcedStep = stepType }

    // Needed by the VIAL branch below; the other steps read these inside their
    // own variant.
    val steps by viewModel.steps.collectAsState()
    val isVoiceOverEnabled by viewModel.isSoundEnabled.collectAsState()
    val isFixed = scanType == CountType.FIXED.toString() &&
        stepType != StepState.CONTAINER_INITIATE

    // VIAL capture gets the same full-bleed treatment as the counting steps —
    // details bar on top, the strip along the bottom. Its capture controls take the
    // place of the tap-to-add count circle: a column down the right edge in
    // landscape, a row above the strip in portrait.
    if (stepType == StepState.VIAL) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // CameraActionBar fills whatever height it is given, so it needs an
            // explicit one. Roughly the share of the screen its old panel had.
            val captureBarHeight = maxHeight * 0.22f
            val captureControls = @Composable {
                CameraActionBar(
                    onRedo = { viewModel.redoCaptureImage() },
                    onCapture = { viewModel.captureImage() },
                    onDone = { viewModel.saveCaptureImage() },
                    viewModel = viewModel,
                )
            }

            CountModeTopDetailsBar(
                ndc = uiState.ndc,
                drugName = drugName,
                strength = uiState.strength,
                bucket = uiState.bucket,
                drugImage = uiState.drugImage,
                showGloveIcon = showGloveIcon,
                glovesDetected = glovesDetected,
            )

            // Landscape puts the capture controls in a column down the right edge,
            // clear of the details bar and the strip. Portrait keeps them as a row
            // directly above the strip (the [leading] slot below).
            if (isLandscape) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight(0.7f)
                        .fillMaxWidth(0.3f)
                ) {
                    captureControls()
                }
            }

            CountModeBottomStrip(
                steps = steps,
                currentStep = stepType,
                isVoiceOverEnabled = isVoiceOverEnabled,
                circleSize = countModeStepSize(isTablet = isTablet, isLandscape = isLandscape),
                isLandscape = isLandscape,
                autoRevealCurrentStep = autoRevealCurrentStep,
                onAutoRevealed = onAutoRevealed,
                isFixed = isFixed,
                totalCount = totalCount,
                targetCount = targetCount,
                onShowHistory = onShowHistory,
                onProceed = onDone,
                onReset = onReset,
                leading = if (isLandscape) null else ({
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(captureBarHeight)
                    ) {
                        captureControls()
                    }
                }),
            )
        }
        return
    }

    // All other counting steps use the shared full-bleed overlay, split by form
    // factor into four variants so each layout can be tuned independently (see
    // CountMode{Phone,Tablet}{Portrait,Landscape}). Mirrors the DashboardScreen
    // form-factor dispatch.
    when {
        isTablet && !isLandscape -> CountModeTabletPortrait(
            totalCount = totalCount,
            targetCount = targetCount,
            scanType = scanType,
            detectedCount = filteredPillCount,
            onAdd = onAdd,
            onDone = onDone,
            viewModel = viewModel,
            drugName = drugName,
            ndc = uiState.ndc,
            strength = uiState.strength,
            bucket = uiState.bucket,
            showHistory = onShowHistory,
            autoRevealCurrentStep = autoRevealCurrentStep,
            onAutoRevealed = onAutoRevealed,
            drugImage = uiState.drugImage,
            showGloveIcon = showGloveIcon,
            glovesDetected = glovesDetected,
            onReset = onReset,
        )

        isTablet && isLandscape -> CountModeTabletLandscape(
            totalCount = totalCount,
            targetCount = targetCount,
            scanType = scanType,
            detectedCount = filteredPillCount,
            onAdd = onAdd,
            onDone = onDone,
            viewModel = viewModel,
            drugName = drugName,
            ndc = uiState.ndc,
            strength = uiState.strength,
            bucket = uiState.bucket,
            showHistory = onShowHistory,
            autoRevealCurrentStep = autoRevealCurrentStep,
            onAutoRevealed = onAutoRevealed,
            drugImage = uiState.drugImage,
            showGloveIcon = showGloveIcon,
            glovesDetected = glovesDetected,
            onReset = onReset,
        )

        !isTablet && !isLandscape -> CountModePhonePortrait(
            totalCount = totalCount,
            targetCount = targetCount,
            scanType = scanType,
            detectedCount = filteredPillCount,
            onAdd = onAdd,
            onDone = onDone,
            viewModel = viewModel,
            drugName = drugName,
            ndc = uiState.ndc,
            strength = uiState.strength,
            bucket = uiState.bucket,
            showHistory = onShowHistory,
            autoRevealCurrentStep = autoRevealCurrentStep,
            onAutoRevealed = onAutoRevealed,
            drugImage = uiState.drugImage,
            showGloveIcon = showGloveIcon,
            glovesDetected = glovesDetected,
            onReset = onReset,
        )

        else -> CountModePhoneLandscape(
            totalCount = totalCount,
            targetCount = targetCount,
            scanType = scanType,
            detectedCount = filteredPillCount,
            onAdd = onAdd,
            onDone = onDone,
            viewModel = viewModel,
            drugName = drugName,
            ndc = uiState.ndc,
            strength = uiState.strength,
            bucket = uiState.bucket,
            showHistory = onShowHistory,
            autoRevealCurrentStep = autoRevealCurrentStep,
            onAutoRevealed = onAutoRevealed,
            drugImage = uiState.drugImage,
            showGloveIcon = showGloveIcon,
            glovesDetected = glovesDetected,
            onReset = onReset,
        )
    }
}