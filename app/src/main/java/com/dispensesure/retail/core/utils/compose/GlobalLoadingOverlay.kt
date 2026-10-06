package com.dispensesure.retail.core.utils.compose

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

private const val DOT_COUNT = 3
private const val BREATH_HALF_CYCLE_MS = 600
private const val DOT_STAGGER_MS = 200

@Composable
fun GlobalLoadingOverlay(
    isVisible: Boolean,
    backgroundColor: Color = Color.Black.copy(alpha = 0.3f),
) {
    if (!isVisible) return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .zIndex(1000f),
        contentAlignment = Alignment.Center,
    ) {
        BreathingDotsLoader()
    }
}

@Composable
fun BreathingDotsLoader(
    modifier: Modifier = Modifier,
    dotSize: Dp = 24.dp,
    color: Color = MaterialTheme.colorScheme.secondary,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "breathing_dots")

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(dotSize / 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(DOT_COUNT) { index ->
            val breath by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = BREATH_HALF_CYCLE_MS, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(index * DOT_STAGGER_MS),
                ),
                label = "dot_breath_$index",
            )

            Box(
                modifier = Modifier
                    .size(dotSize)
                    .graphicsLayer {
                        val scale = 0.6f + 0.4f * breath
                        scaleX = scale
                        scaleY = scale
                        alpha = 0.3f + 0.7f * breath
                    }
                    .background(
                        color = color,
                        shape = CircleShape,
                    ),
            )
        }
    }
}
