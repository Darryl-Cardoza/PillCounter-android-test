package com.dispensesure.retail.core.utils.compose

import androidx.annotation.DrawableRes
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.dispensesure.retail.R
import com.dispensesure.retail.core.models.isControlledDrugType
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.responsiveDp
import com.dispensesure.retail.ui.theme.AppTheme
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
    val strength: String? = null,
    val dosageForm: String? = null,
    /** Absolute local path to the downloaded drug image (.webp). Shown first when available. */
    val drugImagePath: String? = null,
    val rxNo: String? = null,
    val refillNo: String? = null,
)

private const val IdentifierLineSeparator = "  •  "

// Second line of a drug row: "Rx 7654321-2  •  340B  •  CII".
// NDC stands in when there is no Rx number; missing parts are dropped.
internal fun DrugCountRowData.buildIdentifierLine(
    rxLabel: (String) -> String,
    ndcLabel: (String) -> String,
): String {
    val rx = rxNo.trimmedOrNull()
    val ndcValue = ndc.trimmedOrNull()
    val id = when {
        rx != null -> rxLabel(listOfNotNull(rx, refillNo.trimmedOrNull()).joinToString("-"))
        ndcValue != null -> ndcLabel(ndcValue)
        else -> null
    }
    // Only DEA schedule codes (CII–CVI) render; drugType can hold other values.
    val scheduleCode = drugType.trimmedOrNull()?.uppercase()?.takeIf { isControlledDrugType(it) }
    return listOfNotNull(id, bucketId.trimmedOrNull(), scheduleCode).joinToString(IdentifierLineSeparator)
}

// Trimmed value, or null when missing or blank.
private fun String?.trimmedOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

@Composable
fun DrugCountRowData.identifierLine(): String {
    val resources = LocalResources.current
    return buildIdentifierLine(
        rxLabel = { resources.getString(R.string.rx_value, it) },
        ndcLabel = { resources.getString(R.string.ndc_value, it) },
    )
}

/**
 * Picks a vector icon for a dosage form string (e.g. "CAPSULE, EXTENDED RELEASE").
 * Defaults to the capsule icon for unknown / missing forms.
 */
@DrawableRes
internal fun dosageFormIcon(dosageForm: String?): Int {
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
                val isTablet = UserInterfaceUtils.isTablet()
                DrugThumbnail(
                    data = data,
                    modifier = Modifier
                        .width(if (isTablet) 80.dp else 60.dp)
                        .height(if (isTablet) 74.dp else 56.dp),
                )

                Spacer(modifier = Modifier.width(12.dp))

                // drug name | Rx + refill + bucket + schedule | date
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = data.drugName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val idLine = data.identifierLine()
                    if (idLine.isNotEmpty()) {
                        Text(
                            text = idLine,
                            fontSize = 12.sp,
                            color = AppTheme.extendedColors.textColor.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = data.date,
                            fontSize = 12.sp,
                            color = AppTheme.extendedColors.textColor.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(dimens.extraSmall))

                // count
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val countText = if (data.isDispense) {
                        "${data.targetCount}"
                    } else {
                        "${data.pillCount}"
                    }
                    Text(
                        text = countText,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

// Drug picture tile shared by DrugCountRow and the dashboard "UP NEXT" card.
// The caller sets the size; backgroundColor fills the tile behind the picture.
@Composable
fun DrugThumbnail(
    data: DrugCountRowData,
    modifier: Modifier = Modifier,
    backgroundColor: Color = AppTheme.extendedColors.primaryBackground,
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
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor),
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
                            .size(240, 192) // 3x the largest (80dp-wide tablet) tile; Coil downsamples on decode
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
}
