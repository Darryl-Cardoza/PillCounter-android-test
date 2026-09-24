package com.dispensesure.retail.feature.dispenseFlow.presentation.compose

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dispensesure.retail.R
import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.room.models.enums.CountType
import com.dispensesure.retail.core.scanning.presentation.viewmodel.PillScanningViewModel

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
    totalCount: Int,
    targetCount: Int,
    scanType: String,
    detectedCount: Int,
    onAdd: () -> Unit,
    onDone: () -> Unit,
    viewModel: PillScanningViewModel,
    drugName: String,
    ndc: String,
    strength: String,
    bucket: String,
    showHistory: () -> Unit,
    autoRevealCurrentStep: Boolean,
    onAutoRevealed: () -> Unit,
    drugImage: String? = "",
    showGloveIcon: Boolean = false,
    glovesDetected: Boolean = false,
    onReset: (() -> Unit)? = null,
) {
    val uiState by viewModel.uiState.collectAsState()
    val stepType by viewModel.currentStep.collectAsState()
    val steps by viewModel.steps.collectAsState()
    val isVoiceOverEnabled by viewModel.isSoundEnabled.collectAsState()

    val isFixed = scanType == CountType.FIXED.toString() &&
        stepType != StepState.CONTAINER_INITIATE
    val isAllDone = isFixed && targetCount > 0 && totalCount >= targetCount
    val isRegular = scanType == CountType.REGULAR.toString()
    // Stock count (REGULAR) has no target count, so the centre circle never
    // reaches "All Done" — surface a Proceed button to finish the count instead.
    val showProceed = stepType == StepState.CONTAINER_INITIATE ||
        stepType == StepState.CONTAINER_PENDING ||
        isRegular

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {

        CountModeTopDetailsBar(
            ndc = ndc,
            drugName = drugName,
            strength = strength,
            bucket = bucket,
            drugImage = drugImage,
            showGloveIcon = showGloveIcon,
            glovesDetected = glovesDetected,
        )

        CountModeCenterCircle(
            detectedCount = detectedCount,
            viewModel = viewModel,
            isAllDone = isAllDone,
            isAddCooldown = uiState.isAddCooldown,
            onAdd = onAdd,
            onDone = onDone,
        )

        CountModeBottomStrip(
            steps = steps,
            currentStep = stepType,
            isVoiceOverEnabled = isVoiceOverEnabled,
            circleSize = PHONE_LANDSCAPE_STEP_SIZE,
            isLandscape = true,
            autoRevealCurrentStep = autoRevealCurrentStep,
            onAutoRevealed = onAutoRevealed,
            isFixed = isFixed,
            totalCount = totalCount,
            targetCount = targetCount,
            onShowHistory = showHistory,
            onProceed = onDone,
            // REGULAR labels the counting step "Scan Open Pills".
            titleOverrides = if (isRegular) {
                mapOf(StepState.TARGET_VERIFICATION to R.string.scan_open_pills)
            } else {
                emptyMap()
            },
            showProceed = showProceed,
            onReset = onReset,
        )
    }
}
