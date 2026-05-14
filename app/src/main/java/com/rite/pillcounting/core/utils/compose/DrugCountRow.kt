package com.rite.pillcounting.core.utils.compose

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.graphics.Color
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
    val dimens = AppTheme.dimens
    val progress = when {
        data.countType == CountType.FIXED && data.targetCount > 0 ->
            (data.pillCount.toFloat() / data.targetCount.toFloat()).coerceIn(0f, 1f)

        data.countType == CountType.REGULAR -> 1f
        else -> 0f
    }
    val animatedProgress by animateFloatAsState(progress)
    val isActive = multiSelectMode && isSelected
    val selectionColor = MaterialTheme.colorScheme.secondary

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .cardSelectionShadow(isActive = isActive, selectionColor = selectionColor)
            .background(
                color = AppTheme.extendedColors.secondaryBackground,
                shape = RoundedCornerShape(dimens.small)
            )
            .clickable { if (multiSelectMode) onSelectChange() else onClick() }
    ) {
        Card(
            shape = RoundedCornerShape(dimens.small),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
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
                        fontWeight = FontWeight.SemiBold,
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

                Spacer(modifier = Modifier.width(dimens.extraSmall))

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