package com.rite.pillcounting.core.utils.compose

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.rite.pillcounting.R
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveDp
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
    val isDispense: Boolean,
    val isComingFromHL7: Boolean = false,
    val strength: String? = null,
    val dosageForm: String? = null,
    /** Absolute local path to the downloaded drug image (.webp). Shown first when available. */
    val drugImagePath: String? = null,
)

/**
 * Picks a vector icon for a dosage form string (e.g. "CAPSULE, EXTENDED RELEASE").
 * Defaults to the capsule icon for unknown / missing forms.
 */
@DrawableRes
private fun dosageFormIcon(dosageForm: String?): Int {
    val form = dosageForm?.uppercase().orEmpty()
    return when {
        "CAPSULE" in form -> R.drawable.pill_icon_48
        "TABLET" in form -> R.drawable.pill_tablet
        else -> R.drawable.pill_icon_48
    }
}

@Composable
fun DrugCountRow(
    data: DrugCountRowData,
    onClick: () -> Unit,
    multiSelectMode: Boolean = false,
    isSelected: Boolean = false,
    onSelectChange: () -> Unit = {}
) {
    val dimens = AppTheme.dimens
    val animatedProgress by animateFloatAsState(
        targetValue = when {
            data.isDispense && data.targetCount > 0 ->
                (data.pillCount.toFloat() / data.targetCount.toFloat()).coerceAtMost(1f)

            !data.isDispense -> 1f
            else -> 0f
        },
        label = "progress"
    )
    val isActive = multiSelectMode && isSelected
    val selectionColor = MaterialTheme.colorScheme.secondary

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .cardSelectionShadow(isActive = isActive, selectionColor = selectionColor)
            .background(
                color = AppTheme.extendedColors.secondaryBackground,
                shape = RoundedCornerShape(dimens.extraSmall)
            )
            .clickable { if (multiSelectMode) onSelectChange() else onClick() }
    ) {
        Card(
            shape = RoundedCornerShape(dimens.extraSmall),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp, horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left tile priority:
                // 1. Drug image (API webp downloaded locally) — highest priority
                // 2. Dosage-form icon + strength badge
                // 3. Captured barcode image
                // 4. Generic prescription icon
                val hasDrugImage = !data.drugImagePath.isNullOrBlank() &&
                        File(data.drugImagePath).let { it.exists() && it.length() > 0 }
                val hasFormInfo = !data.dosageForm.isNullOrBlank() || !data.strength.isNullOrBlank()
                Box(
                    modifier = Modifier
                        .width(80.dp)
                        .height(74.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(AppTheme.extendedColors.primaryBackground),
                    contentAlignment = Alignment.Center
                ) {
                    val hasImage = !data.barcodeImage.isNullOrEmpty()
                    when {
                        hasDrugImage -> {
                            Image(
                                painter = rememberAsyncImagePainter(
                                    ImageRequest.Builder(LocalContext.current)
                                        .data(File(data.drugImagePath))
                                        .size(240, 222)
                                        .placeholder(R.drawable.prescription_icon)
                                        .error(R.drawable.prescription_icon)
                                        .build()
                                ),
                                contentDescription = data.drugName,
                                contentScale = ContentScale.FillBounds,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(8.dp))
                            )
                        }

                        hasFormInfo -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    painter = painterResource(dosageFormIcon(data.dosageForm)),
                                    contentDescription = data.dosageForm,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(responsiveDp(20.dp))
                                )
                                if (!data.strength.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(1.dp))
                                    Text(
                                        text = data.strength,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary,
                                        textAlign = TextAlign.Center,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        hasImage -> {
                            Image(
                                painter = rememberAsyncImagePainter(
                                    ImageRequest.Builder(LocalContext.current)
                                        .data(File(data.barcodeImage ?: ""))
                                        .size(240, 192) // 3x the 80x64dp display box; Coil downsamples on decode
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
                        }

                        else -> {
                            Icon(
                                painter = painterResource(R.drawable.prescription_icon),
                                contentDescription = null,
                                tint = AppTheme.extendedColors.textColor.copy(alpha = 0.8f),
                                modifier = Modifier.size(36.dp)
                            )
                        }
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
//                            if (!data.drugType.isNullOrBlank() && data.drugType != "null") {
//                                Spacer(modifier = Modifier.width(8.dp))
//                                Text(
//                                    text = data.drugType,
//                                    fontSize = 14.sp,
//                                    fontWeight = FontWeight.SemiBold,
//                                    color = AppTheme.extendedColors.textColor,
//                                    maxLines = 1,
//                                    softWrap = false
//                                )
//                            }
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
//                        if (data.isComingFromHL7) {
//                            Spacer(modifier = Modifier.width(8.dp))
//                            Text(
//                                text = stringResource(R.string.pms),
//                                fontSize = 12.sp,
//                                color = MaterialTheme.colorScheme.primary,
//                                maxLines = 1,
//                                softWrap = false
//                            )
//                        }
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
                    val countText = if (data.isDispense) {
                        "${data.pillCount}/${data.targetCount}"
                    } else {
                        "${data.pillCount}"
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