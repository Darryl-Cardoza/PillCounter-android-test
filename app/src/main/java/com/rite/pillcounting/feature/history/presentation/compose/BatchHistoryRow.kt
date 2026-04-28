package com.rite.pillcounting.feature.history.presentation.compose

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rite.pillcounting.R
import com.rite.pillcounting.core.utils.common.DateFormats
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.toFormattedDate
import com.rite.pillcounting.core.utils.common.formatDateToUSFormat
import com.rite.pillcounting.core.utils.constants.Dimens.small
import com.rite.pillcounting.feature.history.domain.model.BatchSummary
import com.rite.pillcounting.ui.theme.AppTheme

@Composable
fun BatchHistoryRow(
    summary: BatchSummary,
    onBatchClick: () -> Unit
) {
    val date = summary.createdAt.toFormattedDate()

    Card(
        shape = RoundedCornerShape(small),
        colors = CardDefaults.cardColors(
            containerColor = AppTheme.extendedColors.secondaryBackground
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onBatchClick)
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
                        if (summary.requestIdFromPMS != null) R.drawable.prescription_icon
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
                    text = summary.batchId.toString(),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatDateToUSFormat(
                            date,
                            outputPattern = DateFormats.MM_DD_YYYY_HH_MM_A
                        ),
                        fontSize = 12.sp,
                        color = AppTheme.extendedColors.textColor.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.width(12.dp))
                    if (!summary.bucketId.isNullOrBlank()) {
                        Text(
                            text = summary.bucketId,
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
                    text = summary.uniqueNdcCount.toString(),
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
