package com.rite.pillcounting.feature.history.presentation.compose

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rite.pillcounting.R
import com.rite.pillcounting.core.room.models.enums.CountStatus
import com.rite.pillcounting.core.utils.common.DateFormats
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.toFormattedDate
import com.rite.pillcounting.core.utils.common.formatDateToUSFormat
import com.rite.pillcounting.feature.history.domain.model.TxnWithDrugDto
import com.rite.pillcounting.ui.theme.AppTheme

/**
 * Row item representing a single medicine count entry in history.
 * Purely UI – all data (timestamps, count, etc.) is passed in from the ViewModel.
 *
 * @param rowData Data object for this row.
 * @param appTheme App theme wrapper for extended colors.
 */
@Composable
fun StockRow(
    rowData: TxnWithDrugDto,
    onTxnClick: () -> Unit
) {
    val dimens = AppTheme.dimens
    val date =rowData.createdAt.toFormattedDate()
    Card(
        shape = RoundedCornerShape(dimens.small),
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
                    .padding(vertical = 12.dp, horizontal = 8.dp)
                    .clickable(onClick = onTxnClick),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Medicine thumbnail/logo
                Box(
                    modifier = Modifier
                        .size(55.dp)
                        .background(
                            color = AppTheme.extendedColors.primaryBackground,
                            shape = RoundedCornerShape(10.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.stock),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(18.dp)
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
                        modifier = Modifier.size(20.dp)
                    )
                }



                Spacer(Modifier.width(12.dp))

                // Count value

                Text(
                    text = rowData.pillCount.toString(),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTheme.extendedColors.textColor,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }
    }
}
