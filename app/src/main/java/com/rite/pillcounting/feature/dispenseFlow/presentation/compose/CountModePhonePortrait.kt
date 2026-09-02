package com.rite.pillcounting.feature.dispenseFlow.presentation.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.rite.pillcounting.R
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.scanning.presentation.viewmodel.PillScanningViewModel
import com.rite.pillcounting.core.utils.compose.WorkflowStepper

/** Step circle diameter for the phone-portrait stepper. */
private val PHONE_PORTRAIT_STEP_SIZE = 26.dp

/**
 * Phone, portrait. Full-bleed count overlay over the camera feed.
 *
 * Layout (top → bottom):
 *  - Top translucent details bar (shared).
 *  - Centre draggable count circle = the Add / All-Done action (shared).
 *  - Workflow steps floating directly on the feed (no background).
 *  - A single translucent bottom row: "View all counts" | progress | count |
 *    context button (Proceed for container steps, Done once a FIXED target is met).
 *
 * Landscape is intentionally unchanged and lives in [CountModePhoneLandscape].
 */
@Composable
fun CountModePhonePortrait(
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

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            // Steps float on the camera feed — no black background behind them.
            WorkflowStepper(
                steps = steps,
                currentStep = stepType,
                isVoiceOverEnabled = isVoiceOverEnabled,
                circleSize = PHONE_PORTRAIT_STEP_SIZE,
                // Announce every counting step on entry (and on step change) via the
                // stepper bubble + voiceover — the header stays silent for these
                // steps. REGULAR also labels the counting step "Scan Open Pills".
                autoRevealCurrentStep = autoRevealCurrentStep,
                onAutoRevealed = onAutoRevealed,
                titleOverrides = if (isRegular) {
                    mapOf(StepState.TARGET_VERIFICATION to R.string.scan_open_pills)
                } else {
                    emptyMap()
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))

            // Translucent row: View all counts | progress | count | context button.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(CountModeBarBackground)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ViewAllCountsLink(onClick = showHistory)
                Spacer(Modifier.width(12.dp))
                BottomProgressAndCount(
                    isFixed = isFixed,
                    totalCount = totalCount,
                    targetCount = targetCount,
                    showProceed = showProceed,
                    onProceed = onDone,
                    pushCountToEnd = true,
                )
            }
        }
    }
}
