package com.rite.pillcounting.core.utils.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rite.pillcounting.R
import com.rite.pillcounting.ui.theme.AppTheme
import com.rite.pillcounting.ui.theme.inputBackground
import kotlinx.coroutines.delay

private const val AUTO_COLLAPSE_MS = 5_000L
private val BORDER_WIDTH = 5.dp
private val PIE_SIZE = 32.dp
private val OVERLAY_PADDING = 16.dp

/**
 * App-wide overlay shown while the app is in the OFFLINE health state.
 *
 * Description:
 * Draws a thin themed border around the entire screen and a floating
 * hourglass indicator in the bottom-left corner. The top chamber starts full
 * at the moment the app goes OFFLINE and drains into the bottom chamber as
 * the remaining time before session expiry counts down. Each per-second tick
 * animates smoothly via a 1-second linear tween. On first entry to OFFLINE
 * an animated message pill expands next to the hourglass showing
 * `App will log out in HH:mm:ss`. It auto-collapses after ~5s. Tapping the
 * hourglass toggles the pill open/closed.
 *
 * What it does:
 * - Ticks every second to recompute the remaining ms from the last successful
 *   `/health` timestamp and the offline threshold.
 * - Feeds the fraction into [OfflineHourglassIndicator] which interpolates
 *   the sand-level animation.
 * - Every time the pill enters the expanded state (initial OFFLINE entry OR a
 *   subsequent tap-open) it auto-collapses [AUTO_COLLAPSE_MS] later. If the
 *   user taps to close before the delay elapses, the pending auto-collapse is
 *   cancelled and the pill stays closed until tapped again.
 *
 * @param lastHealthAt Wall-clock ms of the last successful `/health` call
 *   (from `SessionHealthController.lastHealthAt`). `0L` if never succeeded.
 * @param thresholdMs Offline threshold in ms
 *   (from `SessionHealthController.thresholdMs`).
 * @param resetKey Value that changes on every HEALTHY -> OFFLINE transition so
 *   the message pill re-expands. Caller decides the shape (e.g. a timestamp).
 * @param modifier Optional modifier applied to the outer fill-size Box.
 *
 * Example Usage:
 * if (health == HealthState.OFFLINE) {
 *     OfflineOverlay(
 *         lastHealthAt = last,
 *         thresholdMs  = threshold,
 *         resetKey     = enteredOfflineAt,
 *     )
 * }
 */
@Composable
fun OfflineOverlay(
    lastHealthAt: Long,
    thresholdMs: Long,
    resetKey: Any,
    modifier: Modifier = Modifier,
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lastHealthAt, thresholdMs) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val remainingMs = computeRemaining(lastHealthAt, thresholdMs, nowMs)

    var expanded by remember(resetKey) { mutableStateOf(true) }
    LaunchedEffect(resetKey, expanded) {
        if (expanded) {
            delay(AUTO_COLLAPSE_MS)
            expanded = false
        }
    }

    val borderColor = MaterialTheme.colorScheme.error
    val pieFillColor = MaterialTheme.colorScheme.secondary
    val pieTrackColor = MaterialTheme.colorScheme.secondaryContainer
    val pillBackground = MaterialTheme.colorScheme.secondary
    val pillContent = inputBackground

    Box(
        modifier = modifier
            .fillMaxSize()
            .border(width = BORDER_WIDTH, color = borderColor),
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(OVERLAY_PADDING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OfflineHourglassIndicator(
                remainingMs = remainingMs,
                totalMs = thresholdMs,
                frameColor = pieTrackColor,
                sandColor = pieFillColor,
                modifier = Modifier
                    .size(PIE_SIZE)
                    .clickable { expanded = !expanded },
            )
            AnimatedVisibility(
                visible = expanded,
                enter = expandHorizontally() + fadeIn(),
                exit = shrinkHorizontally() + fadeOut(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .background(
                                color = pillBackground,
                                shape = RoundedCornerShape(50),
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = stringResource(
                                id = R.string.offline_disconnect_message,
                                formatOfflineCountdown(remainingMs),
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = pillContent,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Draws an animated hourglass whose top chamber sand drains into the bottom
 * chamber as the remaining offline time counts down.
 *
 * Description:
 * Renders the hourglass silhouette as two triangles meeting at the neck
 * (a bowtie stroke), then fills the sand region in [sandColor]:
 * - Top chamber holds sand for the fraction of time still remaining. As time
 *   drains, the sand level in the top chamber drops toward the neck.
 * - Bottom chamber holds sand for the elapsed fraction. Sand piles up from
 *   the base and rises toward the neck as time elapses.
 *
 * What it does:
 * - Computes `fraction = remainingMs / totalMs`.
 * - Smooths the fraction via [animateFloatAsState] with a 1-second linear
 *   tween so each per-second tick from the caller renders as a continuous
 *   sand-fall animation rather than a jump.
 * - Both chambers are rendered as `Path`s cut at the current sand level line
 *   so the trapezoidal / triangular sand shapes conform to the funnel walls.
 *
 * @param remainingMs Remaining ms until session expiry. Clamped to `>= 0`.
 * @param totalMs Total offline threshold in ms. Non-positive renders an
 *   empty top chamber (defensive — settings pushes a positive threshold).
 * @param frameColor Colour of the hourglass outline.
 * @param sandColor Colour of the sand fill in both chambers.
 * @param modifier Layout modifier applied to the Canvas.
 *
 * Example Usage:
 * OfflineHourglassIndicator(remainingMs = 45_000L, totalMs = 60_000L, ...)
 */
@Composable
private fun OfflineHourglassIndicator(
    remainingMs: Long,
    totalMs: Long,
    frameColor: Color,
    sandColor: Color,
    modifier: Modifier = Modifier,
) {
    val safeRemaining = if (remainingMs > 0L) remainingMs else 0L
    val targetFraction = if (totalMs > 0L) {
        (safeRemaining.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tween(durationMillis = 1_000, easing = LinearEasing),
        label = "hourglassSand",
    )
    // Continuous 1-second loop that drives the "grain of sand falling through
    // the neck" visual. Runs regardless of remaining time so the hourglass
    // reads as actively counting even when the sand levels themselves move
    // sub-pixel per second on a long threshold.
    val fallTransition = rememberInfiniteTransition(label = "hourglassFall")
    val fallProgress by fallTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "hourglassFallProgress",
    )

    Canvas(modifier = modifier) {
        val side = size.minDimension
        val cx = size.width / 2f
        val topY = (size.height - side) / 2f
        val bottomY = topY + side
        val midY = topY + side / 2f
        val leftX = cx - side / 2f
        val rightX = cx + side / 2f
        val halfHeight = midY - topY

        // Hourglass frame — bowtie stroke: top triangle + bottom triangle
        // share the neck vertex (cx, midY).
        val frame = Path().apply {
            moveTo(leftX, topY)
            lineTo(rightX, topY)
            lineTo(cx, midY)
            lineTo(rightX, bottomY)
            lineTo(leftX, bottomY)
            lineTo(cx, midY)
            close()
        }
        drawPath(frame, color = frameColor, style = Stroke(width = 1.5.dp.toPx()))

        // Top chamber sand: triangle from level line down to neck. Level line
        // sits (1 - fraction) of the chamber height below the top edge, so at
        // fraction = 1 it hugs the top edge (full sand) and at fraction = 0
        // it coincides with the neck (empty).
        if (animatedFraction > 0f) {
            val topLevelY = topY + (1f - animatedFraction) * halfHeight
            val topT = (topLevelY - topY) / halfHeight
            val topLeftAtLevel = leftX + topT * (cx - leftX)
            val topRightAtLevel = rightX - topT * (rightX - cx)
            val topSand = Path().apply {
                moveTo(topLeftAtLevel, topLevelY)
                lineTo(topRightAtLevel, topLevelY)
                lineTo(cx, midY)
                close()
            }
            drawPath(topSand, color = sandColor)
        }

        // Bottom chamber sand: trapezoid from level line down to the base. As
        // time drains (fraction -> 0) the level rises from the base toward
        // the neck.
        val elapsedFraction = 1f - animatedFraction
        val bottomLevelY = if (elapsedFraction > 0f) {
            val bottomLevel = bottomY - elapsedFraction * halfHeight
            val bottomT = (bottomY - bottomLevel) / halfHeight
            val bottomLeftAtLevel = cx - bottomT * (cx - leftX)
            val bottomRightAtLevel = cx + bottomT * (rightX - cx)
            val bottomSand = Path().apply {
                moveTo(bottomLeftAtLevel, bottomLevel)
                lineTo(bottomRightAtLevel, bottomLevel)
                lineTo(rightX, bottomY)
                lineTo(leftX, bottomY)
                close()
            }
            drawPath(bottomSand, color = sandColor)
            bottomLevel
        } else {
            bottomY
        }

        // Falling grain: a small circle plus a hairline tail dropping from
        // the neck each second. Loops via [fallProgress] regardless of the
        // remaining time so the animation stays visually alive on long
        // thresholds. Suppressed once the top chamber is empty.
        if (animatedFraction > 0f) {
            val grainStartY = midY
            val grainEndY = bottomLevelY
            val grainY = grainStartY + fallProgress * (grainEndY - grainStartY)
            val grainRadius = 1.dp.toPx()
            drawLine(
                color = sandColor,
                start = Offset(cx, grainStartY),
                end = Offset(cx, grainY),
                strokeWidth = 0.5.dp.toPx(),
            )
            drawCircle(
                color = sandColor,
                radius = grainRadius,
                center = Offset(cx, grainY),
            )
        }
    }
}

/**
 * Formats a remaining-time duration in milliseconds into a zero-padded
 * `HH:mm:ss` string.
 *
 * @param remainingMs Remaining ms. Non-positive values render `00:00:00`.
 * @return Zero-padded `HH:mm:ss` string.
 *
 * Example Usage:
 * formatOfflineCountdown(4_530_000L) // "01:15:30"
 */
private fun formatOfflineCountdown(remainingMs: Long): String {
    val safe = if (remainingMs > 0L) remainingMs else 0L
    val totalSeconds = safe / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}

/**
 * Computes the remaining ms until offline threshold expiry.
 *
 * @param lastHealthAt Wall-clock ms of last successful `/health`, or `0L`.
 * @param thresholdMs Threshold in ms.
 * @param nowMs Current wall-clock ms.
 * @return Remaining ms clamped to `>= 0`. Returns [thresholdMs] if
 *   [lastHealthAt] is non-positive.
 */
private fun computeRemaining(lastHealthAt: Long, thresholdMs: Long, nowMs: Long): Long {
    if (lastHealthAt <= 0L) return thresholdMs
    val elapsed = nowMs - lastHealthAt
    val remaining = thresholdMs - elapsed
    return if (remaining > 0L) remaining else 0L
}
