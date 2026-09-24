package com.dispensesure.retail.core.utils.compose

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dispensesure.retail.ui.theme.AppTheme

/** A responsive layout that splits the screen into two sections (top/bottom or left/right) adapting to orientation with rounded corners. */
@Composable
fun SplitResponsive(
    topOrLeft: @Composable () -> Unit,
    bottomOrRight: @Composable () -> Unit,
    portraitRatio: Pair<Float, Float> = 0.5f to 0.5f,
    landscapeRatio: Pair<Float, Float> = 0.5f to 0.5f,
    cornerRadius: Dp = 16.dp,
) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (isLandscape) {
        val (leftWeight, rightWeight) = landscapeRatio
        Row(Modifier.fillMaxSize()) {
            // Left Box
            Box(
                modifier = Modifier
                    .weight(leftWeight)
                    .fillMaxHeight()
            ) { topOrLeft() }

            // Right Box with inner corners rounded
            Box(
                modifier = Modifier
                    .weight(rightWeight)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = cornerRadius, bottomStart = cornerRadius))
                    .background(AppTheme.extendedColors.primaryBackground)
            ) { bottomOrRight() }
        }
    } else {
        val (topWeight, bottomWeight) = portraitRatio
        Column(Modifier.fillMaxSize()) {
            // Top Box
            Box(
                modifier = Modifier
                    .weight(topWeight)
                    .fillMaxWidth()
            ) { topOrLeft() }

            // Bottom Box with inner corners rounded
            Box(
                modifier = Modifier
                    .weight(bottomWeight)
                    .fillMaxWidth()
                    .background(
                        color = AppTheme.extendedColors.primaryBackground,
                        shape = RoundedCornerShape(
                            topStart = cornerRadius,
                            topEnd = cornerRadius
                        )
                    )
            ) { bottomOrRight() }
        }
    }
}
