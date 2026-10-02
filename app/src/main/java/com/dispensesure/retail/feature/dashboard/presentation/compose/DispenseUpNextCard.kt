package com.dispensesure.retail.feature.dashboard.presentation.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.dispensesure.retail.R
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.responsiveDp
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.responsiveSp
import com.dispensesure.retail.core.utils.compose.DrugThumbnail
import com.dispensesure.retail.core.utils.compose.identifierLine
import com.dispensesure.retail.core.utils.compose.sideBorder
import com.dispensesure.retail.feature.dashboard.domain.model.QueueItem
import com.dispensesure.retail.ui.theme.AppTheme
import com.dispensesure.retail.ui.theme.PlayfairDisplay

// Playfair's default line box is tall; 0.8x the font size keeps the one-line name compact.
private const val UpNextDrugNameLineHeightRatio = 0.8f

// Big first card of a dashboard queue: the dispense that should be counted next.
@Composable
internal fun DispenseUpNextCard(
    item: QueueItem.Dispense,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rowData = item.toDrugCountRowData()
    val detailSize = responsiveSp(DashboardBaseSizes.UpNextDetailText)
    // Tablet: date sits under the Rx / NDC line and the count beside it, so there's no footer row.
    val isTablet = UserInterfaceUtils.isTablet()
    Card(
        modifier = modifier
            .fillMaxWidth()
            .sideBorder(
                color = MaterialTheme.colorScheme.primary,
                width = 2.dp,
                cornerRadius = DashboardBaseSizes.UpNextCardCornerRadius,
            )
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(DashboardBaseSizes.UpNextCardCornerRadius),
        colors = CardDefaults.cardColors(containerColor = AppTheme.extendedColors.secondaryBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(responsiveDp(DashboardBaseSizes.UpNextCardPadding)),
            verticalArrangement = Arrangement.spacedBy(responsiveDp(DashboardBaseSizes.SectionGap)),
        ) {
            UpNextHeader(isHighPriority = item.isHighPriority)

            Row(verticalAlignment = if (isTablet) Alignment.Bottom else Alignment.CenterVertically) {
                DrugThumbnail(
                    data = rowData,
                    modifier = Modifier
                        .width(responsiveDp(DashboardBaseSizes.UpNextThumbnailWidth))
                        .height(responsiveDp(DashboardBaseSizes.UpNextThumbnailHeight)),
                    backgroundColor = Color.White,
                )
                Spacer(modifier = Modifier.width(responsiveDp(DashboardBaseSizes.IconToTextGap)))
                Column(modifier = Modifier.weight(1f)) {
                    val drugNameSize = responsiveSp(DashboardBaseSizes.UpNextDrugNameText, boostOnPhone = false)
                    Text(
                        text = rowData.drugName,
                        fontFamily = PlayfairDisplay,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = drugNameSize,
                        lineHeight = drugNameSize * UpNextDrugNameLineHeightRatio,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Same line as the queue rows: "Rx 7654321-2  •  340B  •  CII".
                    val idLine = rowData.identifierLine()
                    if (idLine.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(responsiveDp(DashboardBaseSizes.UpNextTextLineGap)))
                        Text(
                            text = idLine,
                            fontSize = detailSize,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (isTablet) {
                        Spacer(modifier = Modifier.height(responsiveDp(DashboardBaseSizes.UpNextTextLineGap)))
                        UpNextDate(date = rowData.date, fontSize = detailSize)
                    }
                }
                if (isTablet) {
                    Spacer(modifier = Modifier.width(responsiveDp(DashboardBaseSizes.IconToTextGap)))
                    UpNextCount(count = rowData.targetCount)
                }
            }

            if (!isTablet) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    UpNextDate(date = rowData.date, fontSize = detailSize, modifier = Modifier.weight(1f))
                    UpNextCount(count = rowData.targetCount)
                }
            }
        }
    }
}

@Composable
private fun UpNextDate(date: String, fontSize: TextUnit, modifier: Modifier = Modifier) {
    Text(
        text = date,
        fontSize = fontSize,
        color = AppTheme.extendedColors.textColor,
        maxLines = 1,
        modifier = modifier,
    )
}

@Composable
private fun UpNextCount(count: Int) {
    Text(
        text = count.toString(),
        fontSize = responsiveSp(DashboardBaseSizes.UpNextCountText),
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.secondary,
        maxLines = 1,
    )
}

// "! HIGH PRIORITY" on the left (only for high-priority rows), "UP NEXT" on the right.
@Composable
private fun UpNextHeader(isHighPriority: Boolean) {
    val labelSize = responsiveSp(DashboardBaseSizes.UpNextLabelText)
    // Fixed height = icon size, so the card doesn't grow when the high-priority icon shows.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(responsiveDp(DashboardBaseSizes.UpNextHeaderIconSize)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isHighPriority) {
            Icon(
                painter = painterResource(R.drawable.priorityhigh),
                contentDescription = null,
                tint = AppTheme.extendedColors.textColor,
                modifier = Modifier.size(responsiveDp(DashboardBaseSizes.UpNextHeaderIconSize)),
            )
            Spacer(modifier = Modifier.width(responsiveDp(DashboardBaseSizes.UpNextHeaderIconToLabelGap)))
            Text(
                text = stringResource(R.string.kpi_high_priority).uppercase(),
                fontSize = labelSize,
                fontWeight = FontWeight.SemiBold,
                color = AppTheme.extendedColors.textColor,
                maxLines = 1,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = stringResource(R.string.up_next),
            fontSize = labelSize,
            fontWeight = FontWeight.SemiBold,
            color = AppTheme.extendedColors.textColor,
            maxLines = 1,
        )
    }
}
