package com.dispensesure.retail.core.scanning.presentation.compose.count

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Step circle diameter for the tablet-portrait stepper (roomier than phone). */
private val TABLET_PORTRAIT_STEP_SIZE = 34.dp

/**
 * Tablet, portrait. Same overlay structure as [CountModePhonePortrait] but with
 * larger workflow-step circles to suit the bigger screen.
 *
 * Layout (top → bottom): top details bar | centre count circle | floating steps |
 * translucent row of "View all counts" | progress | count | context button.
 *
 * Landscape is intentionally unchanged and lives in [CountModeTabletLandscape].
 */
@Composable
fun CountModeTabletPortrait(
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
            circleSize = TABLET_PORTRAIT_STEP_SIZE,
            isLandscape = false,
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
