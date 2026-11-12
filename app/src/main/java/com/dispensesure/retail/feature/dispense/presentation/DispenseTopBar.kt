package com.dispensesure.retail.feature.dispense.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.navigation.NavController
import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.BackButton
import com.dispensesure.retail.core.utils.compose.StepTitleWithSpeech
import com.dispensesure.retail.feature.dispense.domain.model.DispenseStage
import com.dispensesure.retail.feature.dispense.presentation.stage.queuePanelWidthFraction

/** The back arrow and, on the queue and the Rx step, the spoken step title. */
@Composable
fun BoxScope.DispenseTopBar(
    navController: NavController,
    stage: DispenseStage,
    currentStep: StepState,
    showScanDetailsBar: Boolean,
    awaitingResume: Boolean,
    isLandscape: Boolean,
    isTablet: Boolean,
    isSoundEnabled: Boolean,
    onBackArrow: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    // In landscape the right side is occupied by a panel (pill count / queue).
    // Pad the header end by the same fraction so Alignment.Center lands in
    // the middle of the visible camera area rather than the full screen width.
    val headerEndPadding = if (isLandscape) {
        val screenW = configuration.screenWidthDp.dp
        when (stage) {
            DispenseStage.COUNTING -> screenW * 0.3f
            DispenseStage.QUEUE -> screenW * queuePanelWidthFraction(isTablet)
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
            // Camera preview stays full-bleed; only the header chrome is
            // inset so the back arrow and title clear the notch.
            .displayCutoutPadding()
            .padding(top = 8.dp, bottom = 8.dp, end = headerEndPadding),
    ) {
        // During COUNTING the back arrow sits in the left gutter of the
        // details bar (NDC on top, drug name below). The arrow's own 10.dp
        // internal padding makes its bounding box taller than that two-line
        // block, so a plain CenterStart drops the arrow to the drug-name
        // line. Pin it to the top and nudge up so the arrow centre lines up
        // with the centre of the NDC / drug-name block instead. Other stages
        // keep the arrow centred against the step-title header. SCAN aligns the
        // same way, but only when it actually draws that bar.
        val centreOnDetailsBar = stage == DispenseStage.COUNTING ||
            showScanDetailsBar
        BackButton(
            navController = navController,
            showBox = false,
            modifier = if (centreOnDetailsBar) {
                Modifier
                    .align(Alignment.TopStart)
                    .offset(y = (-8).dp)
            } else {
                Modifier.align(Alignment.CenterStart)
            },
            onClick = onBackArrow,
        )
        val headerStepType = when (stage) {
            DispenseStage.QUEUE -> StepState.RX_LABEL
            DispenseStage.PRE_RX -> StepState.RX_LABEL
            DispenseStage.PRE_NDC -> StepState.SCAN
            DispenseStage.COUNTING -> currentStep
        }
        Box(modifier = Modifier.align(Alignment.Center)) {
            // Also suppress while a resume/HL7 entry is still resolving its
            // real start stage: otherwise the default PRE_RX header would
            // both flash AND speak "Scan Rx Label" before snapping to the
            // resumed step. Not composing it here prevents the TTS entirely.
            //
            // Every step from the container scan onwards carries the strip,
            // whose stepper announces the step — so no title chip from
            // PRE_NDC through COUNTING (VIAL was the last one still showing
            // it). QUEUE and PRE_RX keep theirs.
            val suppressHeaderTitle = stage == DispenseStage.COUNTING ||
                stage == DispenseStage.PRE_NDC ||
                awaitingResume
            if (!suppressHeaderTitle) {
                StepTitleWithSpeech(
                    stepType = headerStepType,
                    isSoundOverride = isSoundEnabled,
                )
            }
        }
    }
}
