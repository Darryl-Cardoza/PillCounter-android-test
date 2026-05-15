package com.rite.pillcounting.feature.batchCount.presentation.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rite.pillcounting.R
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveDp
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveSpForBatchScreen
import com.rite.pillcounting.feature.batchCount.domain.model.BatchDrugGroup
import com.rite.pillcounting.feature.batchCount.domain.model.BatchLotEntry
import com.rite.pillcounting.ui.theme.AppTheme

@Composable
fun BatchNdcCard(
    group: BatchDrugGroup,
    isExpanded: Boolean,
    onExpandToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppTheme.extendedColors.secondaryBackground)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onExpandToggle() }
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.drugName,
                    color = AppTheme.extendedColors.textColor,
                    fontSize = responsiveSpForBatchScreen(14.sp),
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = group.ndc,
                    color = AppTheme.extendedColors.textColor.copy(alpha = 0.7f),
                    fontSize = responsiveSpForBatchScreen(12.sp),
                    fontWeight = FontWeight.Normal
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = group.totalCount.toString(),
                    color = MaterialTheme.colorScheme.secondary,
                    fontSize = responsiveSpForBatchScreen(16.sp),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            ) {
                Spacer(Modifier.height(8.dp))
                BatchLotSection(
                    label = stringResource(R.string.sealed_bottles),
                    headerQty = group.sealedBottleQty,
                    total = group.sealedTotal,
                    lots = group.sealedLots
                )
                Spacer(Modifier.height(responsiveDp(20.dp)))
                BatchLotSection(
                    label = stringResource(R.string.opend_bottles),
                    headerQty = null,
                    total = group.openedTotal,
                    lots = group.openedLots
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
fun BatchLotSection(label: String, headerQty: Int?, total: Int, lots: List<BatchLotEntry>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = AppTheme.extendedColors.textColor.copy(alpha = 0.7f),
            fontSize = responsiveSpForBatchScreen(12.sp),
            fontWeight = FontWeight.SemiBold
        )
        if (headerQty != null) {
            Text(
                text = headerQty.toString(),
                color = AppTheme.extendedColors.textColor,
                fontSize = responsiveSpForBatchScreen(12.sp),
                fontWeight = FontWeight.SemiBold
            )
        }
    }
    Spacer(Modifier.height(responsiveDp(12.dp)))
    if (lots.isEmpty()) {
        Text(
            text = "—",
            color = AppTheme.extendedColors.textColor,
            fontSize = responsiveSpForBatchScreen(12.sp),
            modifier = Modifier.padding(start = 8.dp)
        )
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.lot_No),
                    color = AppTheme.extendedColors.textColor,
                    fontSize = responsiveSpForBatchScreen(12.sp),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(3f)
                )
                Text(
                    text = stringResource(R.string.expiry_date),
                    color = AppTheme.extendedColors.textColor,
                    fontSize = responsiveSpForBatchScreen(12.sp),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1.5f)
                )
                Text(
                    text = stringResource(R.string.pills),
                    color = AppTheme.extendedColors.textColor,
                    fontSize = responsiveSpForBatchScreen(12.sp),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1.5f),
                    textAlign = TextAlign.End
                )
            }
            HorizontalDivider(
                color = AppTheme.extendedColors.primaryBackground,
                modifier = Modifier.padding(top = 6.dp)
            )
            lots.forEach { lot ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = responsiveDp(10.dp)),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = lot.lotNo ?: "—",
                        color = AppTheme.extendedColors.textColor.copy(alpha = 0.7f),
                        fontSize = responsiveSpForBatchScreen(12.sp),
                        textAlign = TextAlign.Start,
                        modifier = Modifier.weight(3f)
                    )
                    Text(
                        text = lot.expiry ?: "—",
                        color = AppTheme.extendedColors.textColor.copy(alpha = 0.7f),
                        fontSize = responsiveSpForBatchScreen(12.sp),
                        textAlign = TextAlign.Start,
                        modifier = Modifier.weight(1.5f)
                    )
                    Text(
                        text = lot.count.toString(),
                        color = AppTheme.extendedColors.textColor,
                        fontSize = responsiveSpForBatchScreen(12.sp),
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1.5f)
                    )
                }
                HorizontalDivider(
                    color = AppTheme.extendedColors.primaryBackground
                )
            }
        }
        Spacer(Modifier.height(responsiveDp(8.dp)))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.total_pills),
                color = AppTheme.extendedColors.textColor,
                fontSize = responsiveSpForBatchScreen(12.sp),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = total.toString(),
                color = AppTheme.extendedColors.textColor,
                fontSize = responsiveSpForBatchScreen(12.sp),
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

