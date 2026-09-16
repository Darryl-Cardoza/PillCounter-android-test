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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rite.pillcounting.R
import com.rite.pillcounting.core.scanning.domain.model.BottleInfoJson
import com.rite.pillcounting.core.room.models.enums.BatchStatus
import com.rite.pillcounting.core.room.models.enums.CountStatus
import com.rite.pillcounting.core.utils.common.DateFormats
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveSpForHistoryScreen
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.toFormattedDate
import com.rite.pillcounting.core.utils.common.formatDateToUSFormat
import com.rite.pillcounting.core.utils.compose.DrugCountRow
import com.rite.pillcounting.core.utils.compose.DrugCountRowData
import com.rite.pillcounting.core.utils.compose.StatusChip
import com.rite.pillcounting.feature.history.domain.model.BatchSummary
import com.rite.pillcounting.feature.history.domain.model.HistoryDeleteFilter
import com.rite.pillcounting.feature.history.domain.model.ToggleOption
import com.rite.pillcounting.feature.history.domain.model.TxnWithDrugDto
import com.rite.pillcounting.ui.theme.AppTheme

private enum class StatusFilter { ALL, COMPLETED, PENDING }

@Composable
fun CountsSection(
    counts: List<TxnWithDrugDto>,
    batches: List<BatchSummary>,
    selectedOption: ToggleOption,
    initialShowComplete: Boolean = false,
    isSearchActive: Boolean = false,
    onExportClick: () -> Unit = {},
    onDeleteClick: (HistoryDeleteFilter) -> Unit = {},
    onTxnClick: (Long) -> Unit = {},
    onBatchClick: (Long) -> Unit = {},
    onOptionSelected: (ToggleOption) -> Unit
) {
    var statusFilter by rememberSaveable(initialShowComplete) {
        mutableStateOf(if (initialShowComplete) StatusFilter.COMPLETED else StatusFilter.ALL)
    }

    // ── Dispensed (FIXED) breakdowns ──────────────────────────────────────────
    val dispensedCounts = remember(counts) { counts.filter { it.isDispense } }
    val completedDispensed = remember(dispensedCounts) {
        dispensedCounts.filter {
            it.status == CountStatus.COMPLETED || it.status == CountStatus.FORCE_COMPLETED
        }
    }
    val pendingDispensed = remember(dispensedCounts) {
        dispensedCounts.filter {
            it.status != CountStatus.COMPLETED && it.status != CountStatus.FORCE_COMPLETED
        }
    }
    val filteredDispensed = when (statusFilter) {
        StatusFilter.ALL -> dispensedCounts
        StatusFilter.COMPLETED -> completedDispensed
        StatusFilter.PENDING -> pendingDispensed
    }

    // ── Stock Count (batch) breakdowns ────────────────────────────────────────
    val completedBatches = remember(batches) { batches.filter { it.status == BatchStatus.COMPLETED } }
    val pendingBatches = remember(batches) { batches.filter { it.status == BatchStatus.INPROGRESS } }
    val filteredBatches = when (statusFilter) {
        StatusFilter.ALL -> batches
        StatusFilter.COMPLETED -> completedBatches
        StatusFilter.PENDING -> pendingBatches
    }

    // ── Toggle counts (shown in the pill buttons) ─────────────────────────────
    val dispensedCount = dispensedCounts.size
    val stockCount = batches.size       // number of batches, not REGULAR txns

    // ── Status chip counts switch based on which tab is active ────────────────
    val allCount = if (selectedOption == ToggleOption.STOCK) batches.size else dispensedCounts.size
    val completedCount =
        if (selectedOption == ToggleOption.STOCK) completedBatches.size else completedDispensed.size
    val pendingCount =
        if (selectedOption == ToggleOption.STOCK) pendingBatches.size else pendingDispensed.size

    val isEmpty = when (selectedOption) {
        ToggleOption.DISPENSED -> dispensedCounts.isEmpty()
        else -> batches.isEmpty()
    }
    val dimens = AppTheme.dimens

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
    ) {
        if (!isSearchActive) {
            // Main toggle row
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
                        onOptionSelected = {
                            onOptionSelected(it)
                            statusFilter = StatusFilter.ALL
                        }
                    )
                }

                Spacer(Modifier.width(8.dp))

                if (!isEmpty) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionIcon(
                            iconRes = R.drawable.delete,
                            contentDescription = stringResource(R.string.delete_content_description),
                            onClick = {
                                val filter = when (statusFilter) {
                                    StatusFilter.ALL -> HistoryDeleteFilter.ALL
                                    StatusFilter.COMPLETED -> HistoryDeleteFilter.COMPLETED
                                    StatusFilter.PENDING -> HistoryDeleteFilter.PENDING
                                }
                                onDeleteClick(filter)
                            }
                        )
                    }
                }
            }

            // Status filter chips — counts reflect the active tab
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatusChip(
                    label = stringResource(R.string.filter_all),
                    isSelected = statusFilter == StatusFilter.ALL,
                    onClick = { statusFilter = StatusFilter.ALL },
                    count = allCount
                )
                StatusChip(
                    label = stringResource(R.string.completed),
                    isSelected = statusFilter == StatusFilter.COMPLETED,
                    onClick = { statusFilter = StatusFilter.COMPLETED },
                    count = completedCount
                )
                StatusChip(
                    label = stringResource(R.string.partial),
                    isSelected = statusFilter == StatusFilter.PENDING,
                    onClick = { statusFilter = StatusFilter.PENDING },
                    count = pendingCount
                )
            }

            Spacer(Modifier.height(dimens.small))
        }

        if (isEmpty) {
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
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isSearchActive) {
                    when (selectedOption) {
                        ToggleOption.DISPENSED -> {
                            items(dispensedCounts, key = { it.txnId }) { rowData ->
                                DrugCountRow(
                                    data = DrugCountRowData(
                                        barcodeImage = BottleInfoJson.decode(rowData.bottleInfoListJson).firstOrNull()?.barcodeImagePath,
                                        ndc = rowData.ndc,
                                        drugType = rowData.drugType,
                                        drugName = rowData.drugName ?: "",
                                        date = formatDateToUSFormat(
                                            rowData.createdAt.toFormattedDate(),
                                            outputPattern = DateFormats.MM_DD_YYYY_HH_MM_A
                                        ),
                                        bucketId = rowData.bucketId,
                                        pillCount = rowData.pillCount ?: 0,
                                        targetCount = rowData.targetCount ?: 0,
                                        isDispense = rowData.isDispense,
                                        strength = rowData.strength,
                                        dosageForm = rowData.dosageForm,
                                        drugImagePath = rowData.drugImagePath
                                    ),
                                    onClick = { onTxnClick(rowData.txnId) }
                                )
                            }
                        }
                        else -> {
                            items(batches, key = { it.batchId }) { batch ->
                                BatchHistoryRow(
                                    title = batch.batchId.toString(),
                                    dateTime = formatDateToUSFormat(
                                        batch.createdAt.toFormattedDate(),
                                        outputPattern = DateFormats.MM_DD_YYYY_HH_MM_A
                                    ),
                                    bucketId = batch.bucketId,
                                    count = batch.uniqueNdcCount.toString(),
                                    isPrescription = batch.requestIdFromPMS != null,
                                    onBatchClick = { onBatchClick(batch.batchId) }
                                )
                            }
                        }
                    }
                } else {
                    when (selectedOption) {
                        ToggleOption.DISPENSED -> {
                            items(filteredDispensed, key = { it.txnId }) { rowData ->
                                DrugCountRow(
                                    data = DrugCountRowData(
                                        barcodeImage = BottleInfoJson.decode(rowData.bottleInfoListJson).firstOrNull()?.barcodeImagePath,
                                        ndc = rowData.ndc,
                                        drugType = rowData.drugType,
                                        drugName = rowData.drugName ?: "",
                                        date = formatDateToUSFormat(
                                            rowData.createdAt.toFormattedDate(),
                                            outputPattern = DateFormats.MM_DD_YYYY_HH_MM_A
                                        ),
                                        bucketId = rowData.bucketId,
                                        pillCount = rowData.pillCount ?: 0,
                                        targetCount = rowData.targetCount ?: 0,
                                        isDispense = rowData.isDispense,
                                        strength = rowData.strength,
                                        dosageForm = rowData.dosageForm,
                                        drugImagePath = rowData.drugImagePath
                                    ),
                                    onClick = { onTxnClick(rowData.txnId) }
                                )
                            }
                        }

                        else -> {
                            items(filteredBatches, key = { it.batchId }) { batch ->
                                BatchHistoryRow(
                                    title = batch.batchId.toString(),
                                    dateTime = formatDateToUSFormat(
                                        batch.createdAt.toFormattedDate(),
                                        outputPattern = DateFormats.MM_DD_YYYY_HH_MM_A
                                    ),
                                    bucketId = batch.bucketId,
                                    count = batch.uniqueNdcCount.toString(),
                                    isPrescription = batch.requestIdFromPMS != null,
                                    onBatchClick = { onBatchClick(batch.batchId) }
                                )
                            }
                        }
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
    val stockLabel = stringResource(R.string.stock_count_label)

    val dimens = AppTheme.dimens

    Row(modifier = Modifier.padding(top = 10.dp, bottom = 10.dp)) {
        ToggleItem(
            title = stringResource(R.string.toggle_with_count, dispensedLabel, dispensedCount),
            isSelected = selectedOption == ToggleOption.DISPENSED,
            onClick = { onOptionSelected(ToggleOption.DISPENSED) },
            modifier = Modifier.padding(bottom = dimens.toggleVerticalPadding, top = dimens.toggleVerticalPadding, end = dimens.toggleVerticalPadding)
        )

        ToggleItem(
            title = stringResource(R.string.toggle_with_count, stockLabel, stockCount),
            isSelected = selectedOption == ToggleOption.STOCK,
            onClick = { onOptionSelected(ToggleOption.STOCK) },
            modifier = Modifier.padding(bottom = dimens.toggleVerticalPadding, top = dimens.toggleVerticalPadding)
        )
    }
}

@Composable
fun ToggleItem(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dimens = AppTheme.dimens
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary
                else AppTheme.extendedColors.secondaryBackground
            )
            .clickable { onClick() }
            .padding(horizontal = dimens.medium, vertical = 5.dp)
    ) {
        Text(
            text = title,
            color = if (isSelected) Color.White else AppTheme.extendedColors.textColor,
            fontWeight = FontWeight.Normal,
            fontSize = responsiveSpForHistoryScreen(12.sp)
        )
    }
}

