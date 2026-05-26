package com.rite.pillcounting.feature.pillCountScan.presentation.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.ActionButtonPrimary
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.HollowButton
import com.rite.pillcounting.ui.theme.AppTheme
import kotlinx.coroutines.delay

/* ─────────────────────────  CARD CONTAINER  ───────────────────────── */

/**
 * Rounded white card used for both the recent-counts list and the bottom
 * scanned/summary card. Background matches the figma off-white tint.
 */
@Composable
internal fun StockCountCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
    ) {
        content()
    }
}

/* ─────────────────────────  RECENT COUNTS LIST  ───────────────────────── */

@Composable
internal fun BatchStockCountHeader(
    onScanPills: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Batch Stock Count",
            color = Color(0xFF222222),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        ScanPillsPillButton(onClick = onScanPills)
    }
}

@Composable
private fun ScanPillsPillButton(onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, color, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "SCAN PILLS",
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun RecentCountsLabelRow(
    label: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = Color(0xFF666666),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
internal fun RecentCountsList(
    rows: List<RecentBatchRow>,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(rows) { row ->
            RecentCountRow(row = row)
        }
    }
}

@Composable
private fun RecentCountRow(row: RecentBatchRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFF5F5F5))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.drugName,
                color = Color(0xFF222222),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = row.ndc,
                color = Color(0xFF888888),
                fontSize = 11.sp,
            )
        }
        UnitColumn(value = row.pills.toString(), label = "Pills")
        Spacer(modifier = Modifier.width(14.dp))
        UnitColumn(value = row.bottles.toString(), label = "Bottles")
    }
}

@Composable
private fun UnitColumn(value: String, label: String) {
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = value,
            color = MaterialTheme.colorScheme.secondary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = label,
            color = Color(0xFF888888),
            fontSize = 10.sp,
        )
    }
}

/* ─────────────────────────  SCANNED DRUG CARD  ───────────────────────── */

@Composable
internal fun ScannedDrugCard(
    active: ActiveNdc,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onClear: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(
            text = "SCANNED DRUG DETAILS",
            color = Color(0xFF666666),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(12.dp))

        // Row 1: Drug Name | Bucket
        Row(modifier = Modifier.fillMaxWidth()) {
            DetailField(
                label = "Drug Name",
                value = active.drugName,
                modifier = Modifier.weight(2f),
            )
            DetailField(
                label = "Bucket",
                value = active.bucket,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        // Row 2: NDC | Batch | Expiry
        Row(modifier = Modifier.fillMaxWidth()) {
            DetailField(
                label = "NDC Number",
                value = active.ndc,
                modifier = Modifier.weight(1f),
            )
            DetailField(
                label = "Batch No.",
                value = active.batchNo,
                modifier = Modifier.weight(1f),
            )
            DetailField(
                label = "Expiry Date",
                value = active.expiry,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(14.dp))

        CounterRow(
            count = active.bottles,
            totalPills = active.totalPills,
            onIncrement = onIncrement,
            onDecrement = onDecrement,
        )
        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                HollowButton(
                    text = "CLEAR",
                    onClick = onClear,
                    color = MaterialTheme.colorScheme.primary,
                    fixedWidth = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                ActionButtonPrimary(
                    text = "ADD",
                    onClick = onAdd,
                    color = MaterialTheme.colorScheme.primary,
                    fixedWidth = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun DetailField(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            color = Color(0xFF888888),
            fontSize = 11.sp,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            color = MaterialTheme.colorScheme.secondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/* ─────────────────────────  COUNTER  ───────────────────────── */

/**
 * +/- counter with tap = ±1 and long-press = continuous repeat while held.
 * Floor is 1 — the scanned NDC always represents at least one bottle.
 */
@Composable
internal fun CounterRow(
    count: Int,
    totalPills: Int,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CounterButton(
            icon = false,
            onTick = onDecrement,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(
            modifier = Modifier
                .weight(1.4f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFFFAFAFA)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = count.toString(),
                color = MaterialTheme.colorScheme.secondary,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "$totalPills pills",
                color = Color(0xFF888888),
                fontSize = 11.sp,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        CounterButton(
            icon = true,
            onTick = onIncrement,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

/**
 * Press-and-hold repeating counter button. First tick fires immediately on
 * press; while held, ticks repeat every [REPEAT_INTERVAL_MS] after an initial
 * [INITIAL_DELAY_MS] hold.
 */
@Composable
private fun CounterButton(
    icon: Boolean,
    onTick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    LaunchedEffect(isPressed) {
        if (isPressed) {
            onTick()
            delay(INITIAL_DELAY_MS)
            while (isPressed) {
                onTick()
                delay(REPEAT_INTERVAL_MS)
            }
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFFAFAFA))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (icon) Icons.Default.Add else Icons.Default.Remove,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
    }
}

private const val INITIAL_DELAY_MS = 350L
private const val REPEAT_INTERVAL_MS = 80L

/* ─────────────────────────  SUMMARY CARD  ───────────────────────── */

@Composable
internal fun ScannedSummaryCard(
    totalNdcs: Int,
    totalPills: Int,
    onEndCount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = "SCANNED SUMMARY",
            color = Color(0xFF666666),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SummaryStat(label = "Total NDCs", value = totalNdcs.toString(), modifier = Modifier.weight(1f))
            SummaryStat(label = "Total Pills", value = totalPills.toString(), modifier = Modifier.weight(1f))
            Box {
                ActionButtonPrimary(
                    text = "END COUNT",
                    onClick = onEndCount,
                    color = MaterialTheme.colorScheme.primary,
                    fixedWidth = false,
                )
            }
        }
    }
}

@Composable
private fun SummaryStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            color = Color(0xFF888888),
            fontSize = 11.sp,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            color = MaterialTheme.colorScheme.secondary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
