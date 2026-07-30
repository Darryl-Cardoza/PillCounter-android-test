package com.rite.pillcounting.feature.history.presentation.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.rite.pillcounting.R
import com.rite.pillcounting.core.room.models.dtos.TxnDetailInfo
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveDp
import com.rite.pillcounting.ui.theme.AppTheme

/**
 * Tablet-landscape grid of batch thumbnails, deduplicated by image path so the same
 * barcode/batch photo (e.g. reused across bottle steps) is never rendered twice.
 */
@Composable
fun BatchImageGrid(
    batches: List<TxnDetailInfo>,
    isVial: Boolean,
    onBatchImageClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 4,
) {
    val deduped = batches.distinctBy { it.imagePath }

    if (deduped.isEmpty()) {
        Text(
            text = stringResource(R.string.no_batches_recorded),
            color = AppTheme.extendedColors.textColor,
            fontSize = 12.sp
        )
        return
    }

    val cardHeight = responsiveDp(60.dp)
    val rows = (deduped.size + columns - 1) / columns
    val gridHeight = cardHeight * rows + 8.dp * (rows - 1).coerceAtLeast(0)

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier
            .fillMaxWidth()
            .height(gridHeight),
        userScrollEnabled = false,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(deduped, key = { it.imagePath ?: "idx-${deduped.indexOf(it)}" }) { batch ->
            if (isVial) {
                VialBatchCard(
                    imagePath = batch.imagePath,
                    onClick = { batch.imagePath?.takeIf { it.isNotBlank() }?.let(onBatchImageClick) }
                )
            } else {
                TrayBatchCard(
                    imagePath = batch.imagePath,
                    count = batch.pillCount ?: 0,
                    onClick = { batch.imagePath?.takeIf { it.isNotBlank() }?.let(onBatchImageClick) }
                )
            }
        }
    }
}
