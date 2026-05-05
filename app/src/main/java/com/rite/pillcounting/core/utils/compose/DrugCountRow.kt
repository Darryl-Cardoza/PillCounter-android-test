package com.rite.pillcounting.core.utils.compose

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.rite.pillcounting.R
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.utils.constants.Dimens.extraSmall
import com.rite.pillcounting.core.utils.constants.Dimens.small
import com.rite.pillcounting.ui.theme.AppTheme
import java.io.File

data class DrugCountRowData(
    val barcodeImage: String?,
    val ndc: String?,
    val drugType: String?,
    val drugName: String,
    val date: String,
    val bucketId: String?,
    val pillCount: Int,
    val targetCount: Int,
    val countType: CountType,
    val isComingFromHL7: Boolean = false
)

@Composable
fun DrugCountRow(
    data: DrugCountRowData,
    onClick: () -> Unit,
    multiSelectMode: Boolean = false,
    isSelected: Boolean = false,
    onSelectChange: () -> Unit = {}
) {
    val progress = when {
        data.countType == CountType.FIXED && data.targetCount > 0 ->
            (data.pillCount.toFloat() / data.targetCount.toFloat()).coerceIn(0f, 1f)

        data.countType == CountType.REGULAR -> 1f
        else -> 0f
    }
    val animatedProgress by animateFloatAsState(progress)
    val isActive = multiSelectMode && isSelected
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

                    // thin border stroke
                    clipRect(left = -1f, top = cornerRadius, right = size.width + 1f, bottom = size.height + 1f) {
                        drawRoundRect(
                            color = selectionColor.copy(alpha = 0.6f),
                            topLeft = Offset(-1f, 0f),
                            size = Size(size.width + 2f, size.height + 1f),
                            cornerRadius = CornerRadius(cornerRadius),
                            style = Stroke(width = 1.5.dp.toPx())
                        )
                    }

                    // soft shadow layers — low alpha so they read as shadow, not fill
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
            .clickable { if (multiSelectMode) onSelectChange() else onClick() }
    ) {
        Card(
            shape = RoundedCornerShape(small),
            colors = CardDefaults.cardColors(
                containerColor = Color.Transparent // important.
            ),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp, horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Drug image
                Box(
                    modifier = Modifier
                        .width(80.dp)
                        .height(64.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(AppTheme.extendedColors.primaryBackground),
                    contentAlignment = Alignment.Center
                ) {
                    val hasImage = !data.barcodeImage.isNullOrEmpty()
                    if (hasImage) {
                        Image(
                            painter = rememberAsyncImagePainter(
                                ImageRequest.Builder(LocalContext.current)
                                    .data(File(data.barcodeImage ?: ""))
                                    .placeholder(R.drawable.prescription_icon)
                                    .error(R.drawable.prescription_icon)
                                    .build()
                            ),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(8.dp))
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.prescription_icon),
                            contentDescription = null,
                            tint = AppTheme.extendedColors.textColor.copy(alpha = 0.8f),
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // NDC + drug type | drug name | date + bucket ID + PMS badge
                Column(modifier = Modifier.weight(1f)) {
                    if (!data.ndc.isNullOrBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.ndc).uppercase() + " " + data.ndc,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (!data.drugType.isNullOrBlank() && data.drugType != "null") {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = data.drugType,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AppTheme.extendedColors.textColor,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                    Text(
                        text = data.drugName,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                        color = AppTheme.extendedColors.textColor.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = data.date,
                            fontSize = 12.sp,
                            color = AppTheme.extendedColors.textColor.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (!data.bucketId.isNullOrBlank()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = data.bucketId,
                                fontSize = 12.sp,
                                color = AppTheme.extendedColors.textColor,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                        if (data.isComingFromHL7) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.pms),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(extraSmall))

                // Pie progress + count
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(modifier = Modifier.height(4.dp))
                    DrugPieProgressIndicator(
                        progress = animatedProgress,
                        modifier = Modifier.size(30.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    val countText = when (data.countType) {
                        CountType.REGULAR -> "${data.pillCount}"
                        else -> "${data.pillCount}/${data.targetCount}"
                    }
                    Text(
                        text = countText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppTheme.extendedColors.textColor
                    )

                }
            }
        }
    }
}

@Composable
private fun DrugPieProgressIndicator(
    progress: Float,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.secondary
    val bgColor = AppTheme.extendedColors.primaryBackground

    Canvas(modifier = modifier) {
        drawCircle(color = bgColor)
        if (progress > 0f) {
            drawArc(
                color = primaryColor,
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = true
            )
        }
    }
}
