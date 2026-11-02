package com.dispensesure.retail.feature.dispense.presentation.stage

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import com.dispensesure.retail.R
import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.scanning.presentation.compose.ContainerScanStrip
import com.dispensesure.retail.core.scanning.presentation.compose.count.CountModePhonePortraitDetailsBar
import com.dispensesure.retail.core.scanning.presentation.compose.count.CountModeTopDetailsBar
import com.dispensesure.retail.feature.dispense.domain.model.DispenseFlowUiState

/**
 * SCAN step: details bar on top, workflow stepper along the bottom, so the container
 * scan looks like the counting steps that follow it. The drug details are
 * the ones the PMS order / RX label already gave us — the pill VM has no
 * transaction loaded this early.
 */
@Composable
fun BoxScope.ContainerScanStage(
    flowState: DispenseFlowUiState,
    showDetailsBar: Boolean,
    steps: List<StepState>,
    currentStep: StepState,
    isVoiceOverEnabled: Boolean,
    isTablet: Boolean,
    isLandscape: Boolean,
    autoRevealCurrentStep: Boolean,
    onAutoRevealed: () -> Unit,
    onStripHeightKnown: (Dp) -> Unit,
) {
    if (showDetailsBar) {
        // Same default the counting steps apply, so the bucket does not blank
        // out when a reset lands back on the container scan.
        val defaultBucket = stringResource(R.string.default_bucket)
        // Phone portrait drops the image, strength and bucket onto a second
        // row. Same bar as the steps that follow, so the header does not
        // change shape once the container is scanned.
        if (!isTablet && !isLandscape) {
            CountModePhonePortraitDetailsBar(
                ndc = flowState.ndc,
                drugName = flowState.drugName,
                strength = flowState.ndcStrength.orEmpty(),
                bucket = flowState.selectedBucketId.ifBlank { defaultBucket },
                dosageForm = flowState.ndcDosageForm.orEmpty(),
                drugImage = flowState.drugImage,
                showGloveIcon = false,
                glovesDetected = false,
            )
        } else {
            CountModeTopDetailsBar(
                ndc = flowState.ndc,
                drugName = flowState.drugName,
                strength = flowState.ndcStrength.orEmpty(),
                bucket = flowState.selectedBucketId.ifBlank { defaultBucket },
                drugImage = flowState.drugImage,
            )
        }
    }
    // The same bottom strip the counting steps use. Every counting widget is
    // off — nothing has been counted before a container is scanned — so the
    // steps are all it carries.
    ContainerScanStrip(
        steps = steps,
        currentStep = currentStep,
        isVoiceOverEnabled = isVoiceOverEnabled,
        isTablet = isTablet,
        isLandscape = isLandscape,
        autoRevealCurrentStep = autoRevealCurrentStep,
        onAutoRevealed = onAutoRevealed,
        isFixed = true,
        onHeightKnown = onStripHeightKnown,
    )
}
