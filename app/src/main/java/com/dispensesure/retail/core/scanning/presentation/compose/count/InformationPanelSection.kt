package com.dispensesure.retail.core.scanning.presentation.compose.count

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils

@Composable
fun InformationPanelSection(
    state: CountPanelState,
    onAdd: () -> Unit,
    onDone: () -> Unit,
    onShowHistory: () -> Unit,
    onCountSteady: () -> Unit,
    // VIAL step capture controls.
    onCapture: () -> Unit,
    onRedoCapture: () -> Unit,
    onSaveCapture: () -> Unit,
    onReset: (() -> Unit)? = null,
) {
    val stepType = state.stepType
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Rotation swaps the CountMode variant below and recreates its
    // WorkflowStepper, so the announced step is remembered here instead.
    var announcedStep by remember { mutableStateOf<StepState?>(null) }

    val isTablet = UserInterfaceUtils.isTablet()
    val autoRevealCurrentStep = stepType != announcedStep
    val onAutoRevealed: () -> Unit = { announcedStep = stepType }

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
                    onRedo = onRedoCapture,
                    onCapture = onCapture,
                    onDone = onSaveCapture,
                    hasCapture = state.hasStill,
                    isCapturing = state.isCapturing,
                )
            }

            // Phone portrait drops the image, strength and bucket onto a second row.
            // Same bar as its counting steps, so the header does not change shape when
            // the flow reaches the vial.
            if (!isTablet && !isLandscape) {
                CountModePhonePortraitDetailsBar(
                    ndc = state.ndc,
                    drugName = state.drugName,
                    strength = state.strength,
                    bucket = state.bucket,
                    dosageForm = state.dosageForm,
                    drugImage = state.drugImage,
                    showGloveIcon = state.showGloveIcon,
                    glovesDetected = state.glovesDetected,
                )
            } else {
                CountModeTopDetailsBar(
                    ndc = state.ndc,
                    drugName = state.drugName,
                    strength = state.strength,
                    bucket = state.bucket,
                    drugImage = state.drugImage,
                    showGloveIcon = state.showGloveIcon,
                    glovesDetected = state.glovesDetected,
                )
            }

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
                steps = state.steps,
                currentStep = stepType,
                isVoiceOverEnabled = state.isVoiceOverEnabled,
                circleSize = countModeStepSize(isTablet = isTablet, isLandscape = isLandscape),
                isLandscape = isLandscape,
                autoRevealCurrentStep = autoRevealCurrentStep,
                onAutoRevealed = onAutoRevealed,
                isFixed = state.isFixed,
                totalCount = state.totalCount,
                targetCount = state.targetCount,
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
            state = state,
            onAdd = onAdd,
            onDone = onDone,
            showHistory = onShowHistory,
            autoRevealCurrentStep = autoRevealCurrentStep,
            onAutoRevealed = onAutoRevealed,
            onCountSteady = onCountSteady,
            onReset = onReset,
        )

        isTablet && isLandscape -> CountModeTabletLandscape(
            state = state,
            onAdd = onAdd,
            onDone = onDone,
            showHistory = onShowHistory,
            autoRevealCurrentStep = autoRevealCurrentStep,
            onAutoRevealed = onAutoRevealed,
            onCountSteady = onCountSteady,
            onReset = onReset,
        )

        !isTablet && !isLandscape -> CountModePhonePortrait(
            state = state,
            onAdd = onAdd,
            onDone = onDone,
            showHistory = onShowHistory,
            autoRevealCurrentStep = autoRevealCurrentStep,
            onAutoRevealed = onAutoRevealed,
            onCountSteady = onCountSteady,
            onReset = onReset,
        )

        else -> CountModePhoneLandscape(
            state = state,
            onAdd = onAdd,
            onDone = onDone,
            showHistory = onShowHistory,
            autoRevealCurrentStep = autoRevealCurrentStep,
            onAutoRevealed = onAutoRevealed,
            onCountSteady = onCountSteady,
            onReset = onReset,
        )
    }
}
