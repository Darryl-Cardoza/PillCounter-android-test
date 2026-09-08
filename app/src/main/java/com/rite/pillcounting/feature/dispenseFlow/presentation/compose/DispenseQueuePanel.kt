package com.rite.pillcounting.feature.dispenseFlow.presentation.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rite.pillcounting.R
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.scanning.domain.model.BottleInfoJson
import com.rite.pillcounting.core.utils.compose.DrugCountRow
import com.rite.pillcounting.core.utils.compose.DrugCountRowData
import com.rite.pillcounting.feature.dashboard.domain.model.KpiFilter
import com.rite.pillcounting.feature.dashboard.domain.model.QueueItem
import com.rite.pillcounting.ui.theme.AppTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DATE_FMT = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault())
private fun formatDate(epochMillis: Long): String = DATE_FMT.format(Date(epochMillis))

private data class QueueTab(val filter: KpiFilter, val labelRes: Int)

private val QUEUE_TABS = listOf(
    QueueTab(KpiFilter.DISP_PENDING, R.string.queue_tab_pending),
    QueueTab(KpiFilter.DISP_HIGH_PRIORITY, R.string.queue_tab_priority),
    QueueTab(KpiFilter.DISP_CONTROLLED, R.string.queue_tab_controlled),
    QueueTab(KpiFilter.DISP_HAZARDOUS, R.string.queue_tab_hazardous),
)

@Composable
fun DispenseQueuePanel(
    items: List<QueueItem.Dispense>,
    selectedFilter: KpiFilter?,
    onFilterSelected: (KpiFilter) -> Unit,
    onItemClick: (txnId: Long) -> Unit,
    onHomeClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeFilter = selectedFilter ?: KpiFilter.DISP_PENDING

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppTheme.extendedColors.primaryBackground),
    ) {
        // ── Header row ──────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.todays_queue),
                fontWeight = FontWeight.SemiBold,
                color = AppTheme.extendedColors.textColor,
                fontSize = 24.sp,
            )
        }

        // ── Tab-style filter row ─────────────────────────────────────────────────
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 4.dp),
        ) {
            items(QUEUE_TABS) { tab ->
                val isSelected = activeFilter == tab.filter
                Column(
                    modifier = Modifier
                        .clickable { onFilterSelected(tab.filter) }
                        .padding(horizontal = 12.dp)
                        .width(IntrinsicSize.Max),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(tab.labelRes),
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = AppTheme.extendedColors.textColor,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else Color.Transparent,
                            ),
                    )
                }
            }
        }


        // ── Filtered list ────────────────────────────────────────────────────────
        val filtered: List<QueueItem.Dispense> = when (activeFilter) {
            KpiFilter.DISP_HIGH_PRIORITY -> items.filter { it.isHighPriority }
            KpiFilter.DISP_CONTROLLED -> items.filter { it.isControlled }
            KpiFilter.DISP_HAZARDOUS -> items.filter { it.isHazardous }
            else -> items
        }

        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(32.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Text(
                    text = stringResource(R.string.queue_empty_dispense),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = 8.dp,
                    bottom = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(filtered, key = { "d-${it.txn.txnId}" }) { item ->
                    DrugCountRow(
                        data = DrugCountRowData(
                            barcodeImage = BottleInfoJson.decode(item.txn.bottleInfoListJson).firstOrNull()?.barcodeImagePath,
                            ndc = item.txn.ndc,
                            drugType = item.txn.drugType,
                            drugName = item.txn.drugName ?: "—",
                            date = formatDate(item.txn.createdAt),
                            bucketId = item.txn.bucketId,
                            pillCount = item.txn.totalPillCount,
                            targetCount = item.txn.targetCount ?: 0,
                            isDispense = true,
                            isComingFromHL7 = item.txn.isComingFromHL7,
                            strength = item.txn.strength,
                            dosageForm = item.txn.dosageForm,
                            drugImagePath = item.txn.drugImagePath,
                        ),
                        onClick = { onItemClick(item.txn.txnId) },
                    )
                }
            }
        }
    }
}