package com.rite.pillcounting.core.utils.compose

import android.graphics.BlurMaskFilter
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rite.pillcounting.R
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveDp

@Composable
fun DashBoardIcon(
    outerCircleColor: Color,
    innerColor: Color = MaterialTheme.colorScheme.secondary,
    @DrawableRes innerIconRes: Int = R.drawable.fixed_count_inner,
    modifier: Modifier = Modifier,
    outerSize: Dp = 120.dp,
    innerSize: Dp = 60.dp,
    contentDescription: String? = null
) {
    Box(
        modifier = modifier
            .size(responsiveDp(outerSize))
            .drawBehind {
                val cx = this.size.width / 2f
                val cy = this.size.height / 2f
                val radius = this.size.width / 2f - 2f

                drawIntoCanvas { canvas ->
                    val wideGlow = Paint()
                    wideGlow.asFrameworkPaint().apply {
                        style = android.graphics.Paint.Style.STROKE
                        strokeWidth = 10f
                        maskFilter = BlurMaskFilter(55f, BlurMaskFilter.Blur.OUTER)
                        color = outerCircleColor.copy(alpha = 0.45f).toArgb()
                    }
                    canvas.drawCircle(Offset(cx, cy), radius, wideGlow)

                    val tightGlow = Paint()
                    tightGlow.asFrameworkPaint().apply {
                        style = android.graphics.Paint.Style.STROKE
                        strokeWidth = 6f
                        maskFilter = BlurMaskFilter(22f, BlurMaskFilter.Blur.OUTER)
                        color = outerCircleColor.copy(alpha = 0.95f).toArgb()
                    }
                    canvas.drawCircle(Offset(cx, cy), radius, tightGlow)
                }
            }
    ) {
        Icon(
            painter = painterResource(id = R.drawable.fixed_count_outer),
            contentDescription = contentDescription,
            tint = outerCircleColor,
            modifier = Modifier.size(responsiveDp(outerSize))
        )
        Icon(
            painter = painterResource(id = innerIconRes),
            contentDescription = null,
            tint = innerColor,
            modifier = Modifier
                .size(responsiveDp(innerSize))
                .align(androidx.compose.ui.Alignment.Center)
        )
    }
}