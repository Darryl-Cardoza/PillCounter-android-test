package com.rite.pillcounting.feature.history.presentation.compose

import androidx.compose.animation.animateContentSize
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.rite.pillcounting.R
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.utils.common.DateFormats
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.toFormattedDate
import com.rite.pillcounting.core.utils.common.formatDateToUSFormat
import com.rite.pillcounting.core.utils.constants.Dimens.small
import com.rite.pillcounting.feature.history.domain.model.TxnWithDrugDto
import com.rite.pillcounting.ui.theme.AppTheme
import java.io.File

@Composable
fun CountRow(
    rowData: TxnWithDrugDto,
    onTxnClick: () -> Unit
) {
    val date = rowData.createdAt.toFormattedDate()

    val progress = when {
        rowData.countType == CountType.FIXED &&
                rowData.targetCount != null &&
                rowData.targetCount > 0 -> {
            (rowData.pillCount?.toFloat() ?: 0f) / rowData.targetCount.toFloat()
        }

        rowData.countType == CountType.REGULAR -> 1f
        else -> 0f
    }.coerceIn(0f, 1f)

    val animatedProgress by animateFloatAsState(progress)

    Card(
        shape = RoundedCornerShape(small),
        colors = CardDefaults.cardColors(
            containerColor = AppTheme.extendedColors.secondaryBackground
        ),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(onClick = onTxnClick)
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
                    .height(54.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AppTheme.extendedColors.primaryBackground),
                contentAlignment = Alignment.Center
            ) {
                val hasImage = !rowData.barcodeImage.isNullOrEmpty()

                if (hasImage) {
                    val painter = rememberAsyncImagePainter(
                        ImageRequest.Builder(LocalContext.current)
                            .data(File(rowData.barcodeImage ?: ""))
                            .placeholder(R.drawable.prescription_icon)
                            .error(R.drawable.prescription_icon)
                            .build()
                    )

                    Image(
                        painter = painter,
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

            Spacer(Modifier.width(12.dp))

            // NDC, drug name, date
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = (stringResource(R.string.ndc).toUpperCase() + " " + rowData.ndc) ?: "",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    if (!rowData.drugType.equals("null")) {
                        Text(
                            text = rowData.drugType.toString(),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppTheme.extendedColors.textColor
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                }
                Text(
                    text = rowData.drugName ?: "",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    color = AppTheme.extendedColors.textColor.copy(alpha = 0.8f)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatDateToUSFormat(
                            date,
                            outputPattern = DateFormats.MM_DD_YYYY_HH_MM_A
                        ),
                        fontSize = 12.sp,
                        color = AppTheme.extendedColors.textColor.copy(alpha = 0.8f)
                    )
                    if (!rowData.bucketId.isNullOrBlank()) {
                        Spacer(Modifier.width(20.dp))
                        Text(
                            text = rowData.bucketId,
                            fontSize = 12.sp,
                            color = AppTheme.extendedColors.textColor,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            // Pie progress + count text
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(4.dp))
                PieProgressIndicator(
                    progress = animatedProgress,
                    modifier = Modifier.size(30.dp)
                )
                Spacer(Modifier.height(10.dp))
                val countText = when {
                    rowData.countType == CountType.FIXED ->
                        "${rowData.pillCount ?: 0} / ${rowData.targetCount ?: 0}"

                    else -> "${rowData.pillCount ?: 0}"
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

@Composable
private fun PieProgressIndicator(
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
