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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rite.pillcounting.R
import com.rite.pillcounting.core.utils.compose.cardSelectionShadow
import com.rite.pillcounting.ui.theme.AppTheme

@Composable
fun BatchHistoryRow(
    title: String,
    dateTime: String,
    bucketId: String?,
    count: String,
    isPrescription: Boolean = false,
    isMultiSelectMode: Boolean = false,
    isSelected: Boolean = false,
    onSelect: () -> Unit = {},
    onBatchClick: () -> Unit
) {
    val dimens = AppTheme.dimens
    val isActive = isMultiSelectMode && isSelected
    val selectionColor = MaterialTheme.colorScheme.secondary

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .cardSelectionShadow(isActive = isActive, selectionColor = selectionColor)
            .background(
                color = AppTheme.extendedColors.secondaryBackground,
                shape = RoundedCornerShape(dimens.extraSmall)
            )
            .clickable(onClick = if (isMultiSelectMode) onSelect else onBatchClick)
    ) {
        Card(
            shape = RoundedCornerShape(dimens.extraSmall),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            elevation = CardDefaults.cardElevation(0.dp)
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
                        .height(74.dp)
                        .background(
                            color = AppTheme.extendedColors.primaryBackground,
                            shape = RoundedCornerShape(10.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(
                            if (isPrescription) R.drawable.prescription_icon
                            else R.drawable.stock
                        ),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = dateTime,
                            fontSize = 12.sp,
                            color = AppTheme.extendedColors.textColor.copy(alpha = 0.7f)
                        )
                        Spacer(Modifier.width(12.dp))
                        if (!bucketId.isNullOrBlank()) {
                            Text(
                                text = bucketId,
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
                        text = count,
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
}
