package com.rite.pillcounting.feature.dispenseFlow.presentation.compose

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rite.pillcounting.R
import com.rite.pillcounting.core.scanning.presentation.viewmodel.PillScanningViewModel
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveDpForCircularCountIndicator
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveSp

@Composable
fun CircularCountIndicator(
    count: Int,
    modifier: Modifier = Modifier,
    viewModel: PillScanningViewModel,
    // Optional caption rendered under the count (e.g. "ADD THIS"). Null = no caption.
    label: String? = null,
    // When true (e.g. a FIXED count has met its target) the circle plays an
    // Ookla-style completion "breath": it gently scales down/up while a soft ring
    // ripples outward and fades. Looping, subtle — a finished-state affordance.
    pulsing: Boolean = false,
) {

    val indicatorColor = MaterialTheme.colorScheme.primary
    val lastDetections by viewModel.lastTenDetections.collectAsState()

    val uiState by viewModel.uiState.collectAsState()

    // Derive last 4 values and whether they're same
    val (_, lastFourSame) = remember(lastDetections) {
        val values = lastDetections.toList().takeLast(2)
        val same = values.size == 2 && values.distinct().size == 1
        values to same
    }

    // Infinite animation for the rotating arc
    val infiniteTransition = rememberInfiniteTransition(label = "arcTransition")
    val sweepProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweepProgress"
    )

    LaunchedEffect(lastFourSame) {
        if (lastFourSame) {
            viewModel.playCountSoundIfEnabled()
        }
    }

    // ── Ookla-style completion "breath" + ripple ──────────────────────────────
    // Driven by a single 0→1 looping phase. The scale dips slightly mid-cycle and
    // returns (the "breath"); the ripple radius grows from the circle edge outward
    // while its alpha fades to 0. Only animates while [pulsing]; otherwise held at
    // rest (scale 1, no ripple).
    val breathTransition = rememberInfiniteTransition(label = "breathTransition")
    val breathScale by breathTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.92f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathScale"
    )
    val rippleProgress by breathTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rippleProgress"
    )
    val scale = if (pulsing) breathScale else 1f

    // While the post-Add cooldown is active the circle isn't tappable, so dim it
    // and swap the caption to "Wait.." for immediate feedback that the tap landed
    // and Add is briefly disabled.
    val isCooldown = uiState.isAddCooldown

    Box(
        modifier = modifier
            .size(responsiveDpForCircularCountIndicator())
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (isCooldown) 0.4f else 1f
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 4.dp.toPx()

            // Always-visible border ring (track). It sits under the animated
            // counting arc so the circle has a defined edge even at count == 0;
            // once counting starts the brighter arc below draws over it.
            drawArc(
                color = Color.White.copy(alpha = 0.25f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // No pills detected yet (count == 0) → only the static track above is
            // shown. The sweeping/steady arc appears once a count is detected,
            // which is when its "counting" animation is meaningful.
            if (count > 0) {
                // If last 4 are same or uiState.showIdleOverlay is true, show full circle (steady), else animate
                val sweepAngle =
                    if (lastFourSame || uiState.showIdleOverlay) 360f else 360 * sweepProgress

                drawArc(
                    color = indicatorColor,
                    startAngle = 90f,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }

            // Expanding ripple ring: grows from the centre out to the indicator
            // radius while fading out. Kept within the indicator's own bounds because
            // the surrounding count-circle Box clips to its shape — a ring drawn
            // beyond the edge would be cut off. A second ring offset by half a cycle
            // keeps the ripple continuous rather than pulsing in bursts.
            if (pulsing) {
                val edgeRadius = size.minDimension / 2f
                listOf(rippleProgress, (rippleProgress + 0.5f) % 1f).forEach { p ->
                    drawCircle(
                        color = indicatorColor.copy(alpha = (1f - p) * 0.45f),
                        // Start at ~70% of the radius so the ring reads as emanating
                        // from near the edge, then reach the edge as it fades.
                        radius = edgeRadius * (0.7f + p * 0.3f),
                        style = Stroke(width = strokeWidth)
                    )
                }
            }
        }

        // Inner circle with pill count (+ optional caption under it)
        Box(
            modifier = Modifier
                .fillMaxSize(0.90f)
                .clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // In the "All Done" state ([pulsing]) the count is no longer
                // meaningful — show only the "ALL DONE" caption.
                if (!pulsing) {
                    Text(
                        text = count.toString(),
                        color = Color.White,
                        fontSize = responsiveSp(32.sp)
                    )
                }
                val caption = if (isCooldown) {
                    stringResource(R.string.pill_scanning_wait_button)
                } else {
                    label
                }
                if (caption != null) {
                    Text(
                        text = caption,
                        color = indicatorColor,
                        fontSize = responsiveSp(13.sp),
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp,
                    )
                }
            }
        }
    }
}
