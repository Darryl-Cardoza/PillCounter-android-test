package com.rite.pillcounting.feature.dispenseFlow.presentation.compose

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveDpForCircularCountIndicator
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveSp
import com.rite.pillcounting.core.scanning.presentation.viewmodel.PillScanningViewModel
import com.rite.pillcounting.ui.theme.AppTheme

@Composable
fun CircularCountIndicator(
    count: Int,
    modifier: Modifier = Modifier,
    viewModel: PillScanningViewModel
) {

    val indicatorColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f)
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

    Box(
        modifier = modifier.size(responsiveDpForCircularCountIndicator()),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 4.dp.toPx()

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

        // Inner circle with pill count
        Box(
            modifier = Modifier
                .fillMaxSize(0.90f)
                .clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = count.toString(),
                color = AppTheme.extendedColors.textColor,
                fontSize = responsiveSp(32.sp)
            )
        }
    }
}