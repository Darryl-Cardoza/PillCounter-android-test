package com.rite.pillcounting.feature.history.presentation.compose

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.rite.pillcounting.R
import com.rite.pillcounting.core.room.models.enums.CountStatus
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.utils.common.DateFormats
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.toFormattedDate
import com.rite.pillcounting.core.utils.common.formatDateToUSFormat
import com.rite.pillcounting.core.utils.constants.Dimens.large
import com.rite.pillcounting.core.utils.constants.Dimens.small
import com.rite.pillcounting.feature.history.domain.model.TxnWithDrugDto
import com.rite.pillcounting.ui.theme.AppTheme
import java.io.File

/**
 * Row item representing a single medicine count entry in history.
 * Purely UI – all data (timestamps, count, etc.) is passed in from the ViewModel.
 *
 * @param rowData Data object for this row.
 * @param appTheme App theme wrapper for extended colors.
 */
@Composable
fun CountRow(
    rowData: TxnWithDrugDto,
    onTxnClick: () -> Unit
) {
    val date = rowData.createdAt.toFormattedDate()
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
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, start = 8.dp, end = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Medicine thumbnail/logo
                Box(
                    modifier = Modifier
                        .width(70.dp)
                        .height(56.dp) // slightly larger to make room for border
                        .clip(RoundedCornerShape(8.dp))
                        .border(
                            1.dp,
                            color = colorResource(R.color.border_gray),
                            RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    val hasImage = !rowData.barcodeImage.isNullOrEmpty()

                    val painter = if (hasImage) {
                        val file = File(rowData.barcodeImage ?: "")
                        rememberAsyncImagePainter(
                            ImageRequest.Builder(LocalContext.current)
                                .data(file)
                                .placeholder(R.drawable.bottle)
                                .error(R.drawable.bottle)
                                .build()
                        )
                    } else {
                        painterResource(R.drawable.bottle)
                    }
                    val imageModifier = if (hasImage) {
                        Modifier
                            .fillMaxSize() // full container for placeholder
                            .clip(RoundedCornerShape(8.dp))
                    } else {
                        Modifier
                            .size(36.dp) // smaller for cropped image
                            .clip(RoundedCornerShape(8.dp))
                    }
                    Image(
                        painter = painter,
                        contentDescription = null,
                        contentScale = if (hasImage) ContentScale.Crop else ContentScale.Fit,
                        modifier = imageModifier
                    )
                }

                Spacer(Modifier.width(12.dp))

                // Medicine name + timestamp
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = rowData.drugName.toString(),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = AppTheme.extendedColors.textColor
                    )
                    Text(
                        text = formatDateToUSFormat(
                            date,
                            outputPattern = DateFormats.MM_DD_YYYY_HH_MM_A
                        ),
                        fontSize = 12.sp,
                        color = AppTheme.extendedColors.textColor
                    )
                }

                val iconRes = when {
                    rowData.status == CountStatus.PARTIAL -> R.drawable.partial
                    rowData.status == CountStatus.COMPLETED && !rowData.note.isNullOrBlank() -> R.drawable.notes
                    else -> null
                }

                iconRes?.let {
                    Icon(
                        painter = painterResource(id = it),
                        contentDescription = null, // or provide a description if needed
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(large)
                    )
                }



                Spacer(Modifier.width(12.dp))


            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp, start = 8.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically

            ) {

                Spacer(Modifier.width(12.dp))

                // Count value
                val text = when {
                    rowData.countType == CountType.FIXED -> rowData.pillCount.toString() + " / " + rowData.targetCount.toString()
                    rowData.countType == CountType.REGULAR -> rowData.pillCount.toString()
                    else -> ""
                }

                Text(
                    text = text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTheme.extendedColors.textColor,
                    modifier = Modifier.padding(end = 8.dp)
                )



                val progress = when {
                    rowData.countType == CountType.FIXED &&
                            rowData.targetCount != null &&
                            rowData.targetCount > 0 -> {
                        (rowData.pillCount?.toFloat() ?: 0f) /
                                rowData.targetCount.toFloat()
                    }

                    rowData.countType == CountType.REGULAR -> 1f
                    else -> 0f
                }.coerceIn(0f, 1f)

                val animatedProgress by animateFloatAsState(progress)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(
                            AppTheme.extendedColors.textColor,
                            shape = RoundedCornerShape(2.dp)
                        )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(animatedProgress)
                            .fillMaxHeight()
                            .background(
                                MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(2.dp)
                            )
                    )
                }
            }
        }
    }
}
