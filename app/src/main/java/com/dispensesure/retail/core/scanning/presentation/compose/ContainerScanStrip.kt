package com.dispensesure.retail.core.scanning.presentation.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.scanning.presentation.compose.count.CountModeBottomStrip
import com.dispensesure.retail.core.scanning.presentation.compose.count.countModeStepSize

/**
 * The step strip on the container scan: steps only, nothing counted yet. Reports its
 * real height so the live count circle can sit above it.
 */
@Composable
fun BoxScope.ContainerScanStrip(
    steps: List<StepState>,
    currentStep: StepState,
    isVoiceOverEnabled: Boolean,
    isTablet: Boolean,
    isLandscape: Boolean,
    autoRevealCurrentStep: Boolean,
    onAutoRevealed: () -> Unit,
    isFixed: Boolean,
    onHeightKnown: (Dp) -> Unit,
) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .onSizeChanged { stripSize -> onHeightKnown(with(density) { stripSize.height.toDp() }) },
    ) {
        CountModeBottomStrip(
            steps = steps,
            currentStep = currentStep,
            isVoiceOverEnabled = isVoiceOverEnabled,
            circleSize = countModeStepSize(
                isTablet = isTablet,
                isLandscape = isLandscape,
            ),
            isLandscape = isLandscape,
            autoRevealCurrentStep = autoRevealCurrentStep,
            onAutoRevealed = onAutoRevealed,
            isFixed = isFixed,
            totalCount = 0,
            targetCount = 0,
            onShowHistory = {},
            onProceed = {},
            showViewAllCounts = false,
            showCount = false,
            // Portrait has nothing to put in the bar on SCAN, on any device — the
            // steps float on the feed instead of sitting above an empty strip.
            showBar = isLandscape,
        )
    }
}
