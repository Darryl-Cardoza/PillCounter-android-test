package com.rite.pillcounting.feature.history.presentation.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.utils.constants.Dimens.medium
import com.rite.pillcounting.feature.history.domain.model.ToggleOption
import com.rite.pillcounting.feature.history.domain.model.TxnWithDrugDto
import com.rite.pillcounting.ui.theme.AppTheme

/**
 * Section displaying a list of medicine counts for a selected date.
 * Fully MVVM-compliant:
 * - Receives preformatted row data from the ViewModel.
 * - UI only handles rendering.
 *
 * @param counts List of row data from ViewModel.
 * @param onExportClick Callback when export icon is clicked.
 * @param onDeleteClick Callback when delete icon is clicked.
 * @param onFilterClick Callback when filter icon is clicked.
 * @param onSearchClick Callback when search icon is clicked.
 */
@Composable
fun CountsSection(
    counts: List<TxnWithDrugDto>,
    selectedOption: ToggleOption,
    onExportClick: () -> Unit = {},
    onDeleteClick: () -> Unit = {},
    onFilterClick: () -> Unit = {},
    onTxnClick: (Long) -> Unit = {},
    onBatchClick: (Long) -> Unit = {},
    onSearchClick: () -> Unit = {},
    onOptionSelected: (ToggleOption) -> Unit
) {


    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 5.dp)
    ) {
        Spacer(Modifier.height(10.dp))

        val dispensedCount = counts.count { it.countType == CountType.FIXED }
        val stockCount     = counts.count { it.countType == CountType.REGULAR }

        if (counts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.no_data_found),
                    fontSize = 18.sp,
                    color = AppTheme.extendedColors.textColor.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DispensedStockToggleRow(
                        dispensedCount = dispensedCount,
                        stockCount = stockCount,
                        selectedOption = selectedOption,
                        onOptionSelected = onOptionSelected
                    )
                }

                Spacer(Modifier.width(8.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ActionIcon(
                        iconRes = R.drawable.pdf,
                        contentDescription = stringResource(R.string.export_content_description),
                        onClick = onExportClick
                    )

                    ActionIcon(
                        iconRes = R.drawable.delete,
                        contentDescription = stringResource(R.string.delete_content_description),
                        onClick = onDeleteClick
                    )
                }
            }

            val filteredCounts = when (selectedOption) {
                ToggleOption.DISPENSED -> counts.filter { it.countType == CountType.FIXED }
                ToggleOption.STOCK     -> counts.filter { it.countType == CountType.REGULAR }
                else                   -> counts
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredCounts) { rowData ->
                    if (selectedOption == ToggleOption.DISPENSED) {
                        CountRow(
                            rowData = rowData,
                            onTxnClick = { onTxnClick(rowData.txnId) })
                    } else {
                        StockRow(
                            rowData = rowData,
                            onTxnClick = { onBatchClick(rowData.batchId ?: 0L) })
                    }
                }
            }
        }

    }
}

@Composable
fun DispensedStockToggleRow(
    dispensedCount: Int,
    stockCount: Int,
    selectedOption: ToggleOption,
    onOptionSelected: (ToggleOption) -> Unit
) {
    val dispensedLabel = stringResource(R.string.dispensed)
    val stockLabel = stringResource(R.string.stock)

    Row(
        modifier = Modifier.padding(top = medium, bottom = medium)
    ) {
        ToggleItem(
            title = stringResource(R.string.toggle_with_count, dispensedLabel, dispensedCount),
            isSelected = selectedOption == ToggleOption.DISPENSED,
            onClick = { onOptionSelected(ToggleOption.DISPENSED) }
        )

        Spacer(Modifier.width(8.dp))

        ToggleItem(
            title = stringResource(R.string.toggle_with_count, stockLabel, stockCount),
            isSelected = selectedOption == ToggleOption.STOCK,
            onClick = { onOptionSelected(ToggleOption.STOCK) }
        )
    }
}

@Composable
fun ToggleItem(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (isSelected)
                    MaterialTheme.colorScheme.primary
                else
                    AppTheme.extendedColors.secondaryBackground
            )
            .clickable { onClick() }
            .padding(horizontal = medium, vertical = 5.dp)
    ) {

        Text(
            text = title,
            color = AppTheme.extendedColors.textColor,
            fontWeight = FontWeight.Normal,
            fontSize = 15.sp
        )
    }
}
