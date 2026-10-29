package com.dispensesure.retail.core.scanning.presentation.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.dispensesure.retail.core.scanning.presentation.compose.count.CircularCountIndicator
import kotlinx.coroutines.delay
import java.util.ArrayDeque

// Grace period before hiding the count circle after detections drop to 0,
// so momentary empty frames don't flicker the circle off and on.
private const val COUNT_CIRCLE_HIDE_GRACE_MS = 700L

/**
 * The live count circle before counting starts: shown while pills are in view, hidden
 * a moment after they leave. Right side in landscape, bottom in portrait.
 */
@Composable
fun BoxScope.LiveCountCircle(
    detectedCount: Int,
    pillsInView: Boolean,
    recentDetectionCounts: ArrayDeque<Int>,
    isAddCooldown: Boolean,
    isIdle: Boolean,
    onCountSteady: () -> Unit,
    isLandscape: Boolean,
    // Portrait only: lifts the circle clear of the container-scan strip.
    bottomInset: Dp,
) {
    var showCountCircle by remember { mutableStateOf(false) }
    LaunchedEffect(pillsInView) {
        if (pillsInView) {
            showCountCircle = true
        } else {
            delay(COUNT_CIRCLE_HIDE_GRACE_MS)
            showCountCircle = false
        }
    }
    if (!showCountCircle) return

    Box(
        modifier = if (isLandscape) {
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(0.3f)
        } else {
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.25f)
                // Lift clear of the SCAN strip rather than padding
                // into it, so the circle keeps its size on short screens.
                .offset(y = -bottomInset)
        },
        contentAlignment = Alignment.Center,
    ) {
        CircularCountIndicator(
            count = detectedCount,
            recentDetectionCounts = recentDetectionCounts,
            isAddCooldown = isAddCooldown,
            isIdle = isIdle,
            onCountSteady = onCountSteady,
        )
    }
}
