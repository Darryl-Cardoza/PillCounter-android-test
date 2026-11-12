package com.dispensesure.retail.core.scanning.presentation.compose.count

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Step circle diameter for the phone-landscape stepper. */
private val PHONE_LANDSCAPE_STEP_SIZE = 40.dp

/**
 * Phone, landscape. Full-bleed count overlay over the camera feed.
 *
 * Layout: top details bar | centre count circle | a single translucent bottom bar
 * with everything inline — "View all counts" | workflow steps | progress | count |
 * optional Proceed button (container steps).
 *
 * This is the original landscape layout, kept verbatim. Portrait lives in
 * [CountModePhonePortrait].
 */
@Composable
fun CountModePhoneLandscape(
    state: CountPanelState,
    onAdd: () -> Unit,
    onDone: () -> Unit,
    showHistory: () -> Unit,
    autoRevealCurrentStep: Boolean,
    onAutoRevealed: () -> Unit,
    onCountSteady: () -> Unit,
    onReset: (() -> Unit)? = null,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {

        CountModeTopDetailsBar(
            ndc = state.ndc,
            drugName = state.drugName,
            strength = state.strength,
            bucket = state.bucket,
            drugImage = state.drugImage,
            showGloveIcon = state.showGloveIcon,
            glovesDetected = state.glovesDetected,
        )

        CountModeCenterCircle(
            state = state,
            onAdd = onAdd,
            onDone = onDone,
            onCountSteady = onCountSteady,
        )

        CountModeBottomStrip(
            steps = state.steps,
            currentStep = state.stepType,
            isVoiceOverEnabled = state.isVoiceOverEnabled,
            circleSize = PHONE_LANDSCAPE_STEP_SIZE,
            isLandscape = true,
            autoRevealCurrentStep = autoRevealCurrentStep,
            onAutoRevealed = onAutoRevealed,
            isFixed = state.isFixed,
            totalCount = state.totalCount,
            targetCount = state.targetCount,
            onShowHistory = showHistory,
            onProceed = onDone,
            titleOverrides = state.stepTitleOverrides,
            showProceed = state.showProceed,
            onReset = onReset,
        )
    }
}
