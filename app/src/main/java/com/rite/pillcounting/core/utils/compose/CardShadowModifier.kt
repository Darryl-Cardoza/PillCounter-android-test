package com.rite.pillcounting.core.utils.compose

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rite.pillcounting.core.utils.constants.Dimens.small

/**
 * Applies a soft drop-shadow at rest, and an animated border + glow when [isActive] is true.
 * Mirrors the selection effect used in DrugCountRow.
 */
@Composable
fun Modifier.cardSelectionShadow(
    isActive: Boolean,
    selectionColor: Color,
    cornerRadius: Dp = small
): Modifier {
    val scale by animateFloatAsState(
        targetValue = if (isActive) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f)
    )
    return this
        .graphicsLayer(clip = false, scaleX = scale, scaleY = scale)
        .drawBehind {
            val cr = cornerRadius.toPx()
            if (isActive) {
                val glowLayers = 8
                val bottomMax = 14.dp.toPx()
                val sideMax = 6.dp.toPx()

                clipRect(left = -1f, top = cr, right = size.width + 1f, bottom = size.height + 1f) {
                    drawRoundRect(
                        color = selectionColor.copy(alpha = 0.6f),
                        topLeft = Offset(-1f, 0f),
                        size = Size(size.width + 2f, size.height + 1f),
                        cornerRadius = CornerRadius(cr),
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }

                repeat(glowLayers) { i ->
                    val t = (i + 1).toFloat() / glowLayers.toFloat()
                    val bSpread = t * bottomMax
                    val sSpread = t * sideMax
                    val alpha = 0.22f * (1f - t)
                    clipRect(
                        left = -sSpread,
                        top = cr,
                        right = size.width + sSpread,
                        bottom = size.height + bSpread
                    ) {
                        drawRoundRect(
                            color = selectionColor.copy(alpha = alpha),
                            topLeft = Offset(-sSpread, 0f),
                            size = Size(size.width + sSpread * 2, size.height + bSpread),
                            cornerRadius = CornerRadius(cr + sSpread)
                        )
                    }
                }
            } else {
                repeat(4) { i ->
                    val fraction = (i + 1).toFloat() / 4f
                    val spread = fraction * 6.dp.toPx()
                    drawRoundRect(
                        color = Color.Black.copy(alpha = 0.08f * (1f - fraction)),
                        topLeft = Offset(0f, spread),
                        size = Size(size.width, size.height),
                        cornerRadius = CornerRadius(cr)
                    )
                }
            }
        }
}