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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rite.pillcounting.R
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
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.drugName,
                    color = AppTheme.extendedColors.textColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = group.ndc,
                    color = AppTheme.extendedColors.textColor.copy(alpha = 0.7f),
                    fontSize = 14.sp,
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
                    fontSize = 16.sp,
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
                    .padding(horizontal = 14.dp)
            ) {
                HorizontalDivider(
                    color = AppTheme.extendedColors.textColor.copy(alpha = 0.15f),
                    thickness = 0.5.dp
                )
                Spacer(Modifier.height(8.dp))
                BatchLotSection(
                    label = stringResource(R.string.sealed_bottles),
                    total = group.sealedTotal,
                    lots = group.sealedLots
                )
                Spacer(Modifier.height(8.dp))
                BatchLotSection(
                    label = stringResource(R.string.opend_bottles),
                    total = group.openedTotal,
                    lots = group.openedLots
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
fun BatchLotSection(label: String, total: Int, lots: List<BatchLotEntry>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = AppTheme.extendedColors.textColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal
        )
        Text(
            text = total.toString(),
            color = AppTheme.extendedColors.textColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
    Spacer(Modifier.height(4.dp))
    if (lots.isEmpty()) {
        Text(
            text = "—",
            color = AppTheme.extendedColors.textColor.copy(alpha = 0.7f),
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 8.dp)
        )
    } else {
        lots.forEach { BatchLotRow(it) }
    }
}

@Composable
fun BatchLotRow(lot: BatchLotEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, top = 3.dp, bottom = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Lot ${lot.lotNo ?: "—"}",
            color = AppTheme.extendedColors.textColor.copy(alpha = 0.7f),
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = lot.expiry ?: "—",
            color = AppTheme.extendedColors.textColor.copy(alpha = 0.7f),
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Text(
            text = lot.count.toString(),
            color = AppTheme.extendedColors.textColor,
            fontSize = 14.sp
        )
    }
}
