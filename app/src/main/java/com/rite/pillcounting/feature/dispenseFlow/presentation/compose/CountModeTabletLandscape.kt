package com.rite.pillcounting.feature.dispenseFlow.presentation.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.scanning.presentation.viewmodel.PillScanningViewModel
import com.rite.pillcounting.core.utils.compose.WorkflowStepper

/** Step circle diameter for the tablet-landscape stepper (matches tablet portrait). */
private val TABLET_LANDSCAPE_STEP_SIZE = 34.dp

/**
 * Tablet, landscape. Same overlay structure as [CountModePhoneLandscape] but with
 * larger workflow-step circles to suit the bigger screen.
 *
 * Layout: top details bar | centre count circle | a single translucent bottom bar
 * with everything inline — "View all counts" | workflow steps | progress | count |
 * optional Proceed button (container steps).
 *
 * Portrait lives in [CountModeTabletPortrait].
 */
@Composable
fun CountModeTabletLandscape(
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
    dosageForm: String,
    bucket: String,
    showHistory: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val stepType by viewModel.currentStep.collectAsState()
    val steps by viewModel.steps.collectAsState()
    val isVoiceOverEnabled by viewModel.isSoundEnabled.collectAsState()

    val isFixed = scanType == CountType.FIXED.toString() &&
        stepType != StepState.CONTAINER_INITIATE
    val isAllDone = isFixed && targetCount > 0 && totalCount >= targetCount
    val showProceed = stepType == StepState.CONTAINER_INITIATE ||
        stepType == StepState.CONTAINER_PENDING

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {

        CountModeTopDetailsBar(
            ndc = ndc,
            drugName = drugName,
            strength = strength,
            dosageForm = dosageForm,
            bucket = bucket,
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
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(CountModeBarBackground)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ViewAllCountsLink(onClick = showHistory)

                Spacer(Modifier.width(8.dp))

                // Workflow steps live inline between "View all counts" and the
                // progress bar. Weighted so it shares the leftover space with the
                // progress bar instead of taking a fixed footprint.
                Box(modifier = Modifier.weight(1f)) {
                    WorkflowStepper(
                        steps = steps,
                        currentStep = stepType,
                        isVoiceOverEnabled = isVoiceOverEnabled,
                        circleSize = TABLET_LANDSCAPE_STEP_SIZE,
                    )
                }

                Spacer(Modifier.width(12.dp))

                BottomProgressAndCount(
                    isFixed = isFixed,
                    totalCount = totalCount,
                    targetCount = targetCount,
                    showProceed = showProceed,
                    onProceed = onDone,
                )
            }
        }
    }
}
