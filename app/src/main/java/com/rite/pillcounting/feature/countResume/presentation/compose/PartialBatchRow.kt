package com.rite.pillcounting.feature.countResume.presentation.compose

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rite.pillcounting.R
import com.rite.pillcounting.core.utils.constants.Dimens.large
import com.rite.pillcounting.core.utils.constants.Dimens.medium
import com.rite.pillcounting.ui.theme.AppTheme

@Composable
fun PartialBatchRow(
    title: String,
    dateTime: String,
    bucketId: String,
    count: String,
    isMultiSelectMode: Boolean,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onClick: () -> Unit,
    onMoreClick: () -> Unit
) {

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable {
                if (isMultiSelectMode) onSelect()
                else onClick()
            },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor =
                if (isSelected)
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                else
                    AppTheme.extendedColors.secondaryBackground
        )
    ) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            if (isMultiSelectMode) {

                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onSelect() }
                )

                Spacer(Modifier.width(8.dp))
            }

            Box(
                modifier = Modifier
                    .size(40.dp)
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

            Spacer(Modifier.width(medium))

            Column(modifier = Modifier.weight(1f)) {

                Text(
                    text = title,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTheme.extendedColors.textColor,
                    fontSize = 16.sp
                )

                Row(verticalAlignment = Alignment.CenterVertically) {

                    Text(
                        text = dateTime,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppTheme.extendedColors.textColor.copy(alpha = 0.6f),
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        text = "  •  $bucketId",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Text(
                text = count,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}