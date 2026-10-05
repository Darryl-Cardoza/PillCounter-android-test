package com.dispensesure.retail.core.utils.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Dp

// Primary stroke on the left and right edges, curving into the four corners.
// Drawn over the card; the middle of the top and bottom edges stays open.
fun Modifier.sideBorder(color: Color, width: Dp, cornerRadius: Dp): Modifier = drawWithContent {
    drawContent()
    val strokeWidth = width.toPx()
    val inset = strokeWidth / 2
    val radius = cornerRadius.toPx()
    listOf(0f, size.width - radius).forEach { stripLeft ->
        clipRect(left = stripLeft, top = 0f, right = stripLeft + radius, bottom = size.height) {
            drawRoundRect(
                color = color,
                topLeft = Offset(inset, inset),
                size = Size(size.width - strokeWidth, size.height - strokeWidth),
                cornerRadius = CornerRadius(radius - inset),
                style = Stroke(width = strokeWidth),
            )
        }
    }
}
