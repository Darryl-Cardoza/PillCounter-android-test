package com.dispensesure.retail.core.utils.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.dispensesure.retail.ui.theme.AppTheme
import kotlin.math.max

/** Page colour with the [backgroundBlobs] on top, each fading out to transparent. */
@Composable
fun AuthBackground(modifier: Modifier = Modifier) {
    val isDark = AppTheme.isDarkTheme
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val pageColor = AppTheme.extendedColors.secondaryBackground

    Canvas(modifier.fillMaxSize()) {
        drawRect(pageColor)
        val longerSide = max(size.width, size.height)
        backgroundBlobs.forEach { blob ->
            val color = if (blob.colorRole == BlobColorRole.PRIMARY) primary else secondary
            val alpha = if (isDark) blob.darkAlpha else blob.lightAlpha
            val center = Offset(size.width * blob.centerX, size.height * blob.centerY)
            val radius = longerSide * blob.radius
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = alpha), Color.Transparent),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }
    }
}
