package com.dispensesure.retail.core.utils.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp

// Primary stroke on the left and right edges, curving into the four corners.
// Drawn over the card; the middle of the top and bottom edges stays open.
fun Modifier.sideBorder(
    color: Color,
    width: Dp,
    cornerRadius: Dp,
    // Share of the card width each side covers along the top and bottom before it fades out.
    edgeCoverage: Float = 0.1f,
): Modifier = drawWithContent {
    drawContent()
    val strokeWidth = width.toPx()
    val inset = strokeWidth / 2
    val radius = cornerRadius.toPx()
    // Solid through the corner arc, then fades; kept between the arc and the card's middle.
    val fadeEnd = (size.width * edgeCoverage).coerceAtLeast(radius).coerceAtMost(size.width / 2)
    val solidStop = radius / size.width
    val fadeStop = fadeEnd / size.width
    // Fade to the same color at zero alpha, so the tail doesn't darken on the way out.
    val faded = color.copy(alpha = 0f)
    drawRoundRect(
        brush = Brush.horizontalGradient(
            0f to color,
            solidStop to color,
            fadeStop to faded,
            (1f - fadeStop) to faded,
            (1f - solidStop) to color,
            1f to color,
        ),
        topLeft = Offset(inset, inset),
        size = Size(size.width - strokeWidth, size.height - strokeWidth),
        cornerRadius = CornerRadius(radius - inset),
        style = Stroke(width = strokeWidth),
    )
}
