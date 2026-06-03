package com.rite.pillcounting.feature.dispenseFlow.presentation.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveDp
import com.rite.pillcounting.core.scanning.domain.model.TxnDetail
import com.rite.pillcounting.ui.theme.AppTheme

/**
 * A custom Chip composable to display batch counts.
 *
 * Features:
 * - Shows the main count inside a rounded rectangle.
 * - Displays a circular badge above the chip with the batch number.
 *
 * @param count The main count text to display.
 * @param modifier Optional [Modifier] to apply to the parent container.
.
 */
@Composable
fun Chip(
    txnDetail: TxnDetail,
    modifier: Modifier = Modifier,
    index: Int,
    onDelete: (txnDetailId: Long) -> Unit,
    count: String,
    highlight: Boolean
) {

    var showDialog by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .width(responsiveDp(55.dp))
            .padding(horizontal = 2.dp)
            .height(responsiveDp(80.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Transparent)
            .border(
                width = 1.5.dp,
                color = if (highlight) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable { showDialog = true }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = count,
            color = AppTheme.extendedColors.textColor,
            fontSize = 15.sp
        )
    }

    if (showDialog) {
        TxnDetailDialog(
            batchNumber = index,
            details = txnDetail,
            onDelete = {
                onDelete(txnDetail.txnDetailId)
                showDialog = false
            },
            onDismiss = { showDialog = false },
        )
    }
}