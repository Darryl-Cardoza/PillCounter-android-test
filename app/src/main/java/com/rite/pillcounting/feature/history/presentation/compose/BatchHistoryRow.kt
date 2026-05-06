package com.rite.pillcounting.feature.history.presentation.compose

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rite.pillcounting.R
import com.rite.pillcounting.core.utils.constants.Dimens.small
import com.rite.pillcounting.ui.theme.AppTheme

@Composable
fun BatchHistoryRow(
    title: String,
    dateTime: String,
    bucketId: String?,
    count: String,
    isPrescription: Boolean = false,
    isMultiSelectMode: Boolean = false,
    isSelected: Boolean = false,
    onSelect: () -> Unit = {},
    onBatchClick: () -> Unit
) {
    val isActive = isMultiSelectMode && isSelected
    val selectionColor = MaterialTheme.colorScheme.secondary
    val scale by animateFloatAsState(
        targetValue = if (isActive) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f)
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer(clip = false, scaleX = scale, scaleY = scale)
            .drawBehind {
                val cornerRadius = small.toPx()
                if (isActive) {
                    val glowLayers = 8
                    val bottomMax = 14.dp.toPx()
                    val sideMax = 6.dp.toPx()

                    clipRect(
                        left = -1f,
                        top = cornerRadius,
                        right = size.width + 1f,
                        bottom = size.height + 1f
                    ) {
                        drawRoundRect(
                            color = selectionColor.copy(alpha = 0.6f),
                            topLeft = Offset(-1f, 0f),
                            size = Size(size.width + 2f, size.height + 1f),
                            cornerRadius = CornerRadius(cornerRadius),
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
                            top = cornerRadius,
                            right = size.width + sSpread,
                            bottom = size.height + bSpread
                        ) {
                            drawRoundRect(
                                color = selectionColor.copy(alpha = alpha),
                                topLeft = Offset(-sSpread, 0f),
                                size = Size(size.width + sSpread * 2, size.height + bSpread),
                                cornerRadius = CornerRadius(cornerRadius + sSpread)
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
                            cornerRadius = CornerRadius(cornerRadius)
                        )
                    }
                }
            }
            .background(
                color = AppTheme.extendedColors.secondaryBackground,
                shape = RoundedCornerShape(small)
            )
            .clickable(onClick = if (isMultiSelectMode) onSelect else onBatchClick)
    ) {
        Card(
            shape = RoundedCornerShape(small),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {

                Box(
                    modifier = Modifier
                        .width(80.dp)
                        .height(64.dp)
                        .background(
                            color = AppTheme.extendedColors.primaryBackground,
                            shape = RoundedCornerShape(10.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(
                            if (isPrescription) R.drawable.prescription_icon
                            else R.drawable.stock
                        ),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = dateTime,
                            fontSize = 12.sp,
                            color = AppTheme.extendedColors.textColor.copy(alpha = 0.7f)
                        )
                        Spacer(Modifier.width(12.dp))
                        if (!bucketId.isNullOrBlank()) {
                            Text(
                                text = bucketId,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AppTheme.extendedColors.textColor
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(end = 12.dp)
                ) {
                    Text(
                        text = count,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = stringResource(R.string.ndcs_label),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                        color = AppTheme.extendedColors.textColor.copy(alpha = 0.8f),
                    )
                }
            }
        }
    }
}