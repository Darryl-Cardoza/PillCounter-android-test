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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rite.pillcounting.R
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.ActionButtonPrimary
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.HollowButton
import com.rite.pillcounting.feature.dispenseFlow.presentation.compose.ActiveNdc
import com.rite.pillcounting.feature.dispenseFlow.presentation.compose.RecentBatchRow
import kotlinx.coroutines.delay

/* ─────────────────────────  CARD CONTAINER  ───────────────────────── */

/** Rounded white card used as the surface for both the top and bottom sections. */
@Composable
internal fun StockCountCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(CARD_RADIUS))
            .background(Color.White)
    ) {
        content()
    }
}

internal val CARD_RADIUS: Dp = 13.dp

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
            text = stringResource(R.string.batch_stock_count_title),
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
            .padding(horizontal = 16.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.batch_stock_count_scan_pills),
            color = color,
            fontSize = 11.sp,
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
            color = Color(0xFF888888),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
    }
}

/**
 * White rows with hairline dividers between (no row backgrounds). Matches the
 * Figma "list-on-white-card" look — the surrounding [StockCountCard] supplies
 * the white background.
 */
@Composable
internal fun RecentCountsList(
    rows: List<RecentBatchRow>,
    onRowTapped: (RecentBatchRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(
            items = rows,
            key = { _, row -> row.ndc },
        ) { _, row ->
            RecentCountRow(row = row, onTap = { onRowTapped(row) })
        }
    }
}

@Composable
private fun RecentCountRow(row: RecentBatchRow, onTap: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Card look without shadow(): a real elevation layer forces each row
            // into its own RenderNode, which churns badly during scroll and causes
            // jank. The white fill against the grey sheet + a hairline border reads
            // as a distinct card at no per-frame layer cost.
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .border(1.dp, Color(0xFFE3E3E3), RoundedCornerShape(10.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.drugName,
                color = Color(0xFF222222),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = row.ndc,
                color = Color(0xFFAAAAAA),
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
        UnitColumn(value = row.pills.toString(), label = stringResource(R.string.batch_stock_count_label_pills))
        Spacer(modifier = Modifier.width(14.dp))
        UnitColumn(value = row.bottles.toString(), label = stringResource(R.string.batch_stock_count_label_bottles))
    }
}

/** Number on top in magenta, label below in grey caption. Right-aligned. */
@Composable
private fun UnitColumn(value: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = value,
            color = MaterialTheme.colorScheme.secondary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            color = Color(0xFFAAAAAA),
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
            text = stringResource(R.string.batch_stock_count_scanned_drug_details),
            color = Color(0xFF888888),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(12.dp))

        // Row 1: Drug Name (2x) | Bucket (1x).
        Row(modifier = Modifier.fillMaxWidth()) {
            DetailField(
                label = stringResource(R.string.batch_stock_count_label_drug_name),
                value = active.drugName,
                modifier = Modifier.weight(2f),
            )
            DetailField(
                label = stringResource(R.string.batch_stock_count_label_bucket),
                value = active.bucket,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        // Row 2: NDC (1.2x — wider so the formatted number never wraps) | Batch | Expiry.
        Row(modifier = Modifier.fillMaxWidth()) {
            DetailField(
                label = stringResource(R.string.batch_stock_count_label_ndc),
                value = active.ndc,
                modifier = Modifier.weight(1.2f),
            )
            DetailField(
                label = stringResource(R.string.batch_stock_count_label_batch_no),
                value = active.batchNo,
                modifier = Modifier.weight(1f),
            )
            DetailField(
                label = stringResource(R.string.batch_stock_count_label_expiry),
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

        // CLEAR / ADD: smaller, centered with a gap, NOT stretched to fill.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            Box(modifier = Modifier.width(120.dp)) {
                HollowButton(
                    text = stringResource(R.string.batch_stock_count_clear),
                    onClick = onClear,
                    color = MaterialTheme.colorScheme.primary,
                    fixedWidth = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(modifier = Modifier.width(120.dp)) {
                ActionButtonPrimary(
                    text = stringResource(R.string.batch_stock_count_add),
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
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/* ─────────────────────────  COUNTER  ───────────────────────── */

/**
 * +/- counter with tap = ±1 and long-press = continuous repeat while held.
 * Floor is 1 — the scanned NDC always represents at least one bottle.
 *
 * Center tile is wider than the buttons (≈ 1.6 : 1).
 *
 * @param tileFill optional fill for the +/- side tiles. When null (landscape),
 *   tiles are border-only on white. The portrait card passes a light grey so the
 *   tiles read as filled buttons against the white card (matches Figma).
 * @param compact when true (portrait, per Figma) the row is shorter with smaller
 *   icons and value text so the counter reads tight against the detail fields
 *   instead of dominating the card.
 */
@Composable
internal fun CounterRow(
    count: Int,
    totalPills: Int,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    tileFill: Color? = null,
    compact: Boolean = false,
) {
    val borderColor = Color(0xFFE5E5E5)
    val rowHeight = if (compact) 56.dp else 76.dp
    val iconSize = if (compact) 24.dp else 30.dp
    val valueSize = if (compact) 22.sp else 26.sp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CounterButton(
            icon = false,
            onTick = onDecrement,
            borderColor = borderColor,
            fill = tileFill,
            iconSize = iconSize,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        Column(
            modifier = Modifier
                .weight(1.6f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(12.dp))
                // When the side tiles are filled (portrait, per Figma) the center
                // value tile is borderless white; otherwise (landscape) it keeps
                // the hairline border to stand apart from the white card.
                .then(
                    if (tileFill == null) {
                        Modifier.border(1.dp, borderColor, RoundedCornerShape(12.dp))
                    } else {
                        Modifier
                    }
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = count.toString(),
                color = MaterialTheme.colorScheme.secondary,
                fontSize = valueSize,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.batch_stock_count_pills_suffix, totalPills),
                color = Color(0xFFAAAAAA),
                fontSize = 11.sp,
            )
        }
        CounterButton(
            icon = true,
            onTick = onIncrement,
            borderColor = borderColor,
            iconSize = iconSize,
            fill = tileFill,
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
    borderColor: Color,
    modifier: Modifier = Modifier,
    fill: Color? = null,
    iconSize: Dp = 30.dp,
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
            .clip(RoundedCornerShape(12.dp))
            .then(if (fill != null) Modifier.background(fill) else Modifier)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
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
            modifier = Modifier.size(iconSize),
        )
    }
}

private const val INITIAL_DELAY_MS = 350L
private const val REPEAT_INTERVAL_MS = 80L

/* ─────────────────────────  PORTRAIT SCANNED-DRUG CARD  ───────────────────────── */

/**
 * Portrait layout of the active scanned-NDC details. Same data + callbacks as
 * [ScannedDrugCard]; only the field placement differs to fit the narrower
 * right-hand column of the portrait panel:
 *  - Drug Name (full width)
 *  - NDC Number (full width)
 *  - Batch No. | Expiry Date | Bucket (single row)
 *  - +/- counter
 *  - CLEAR / ADD
 *
 * Renders inside the caller's padded card surface, so it adds none of its own.
 */
@Composable
internal fun ScannedDrugDetailsPortrait(
    active: ActiveNdc,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onClear: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.batch_stock_count_scanned_drug_details),
            color = Color(0xFF888888),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(12.dp))

        DetailField(
            label = stringResource(R.string.batch_stock_count_label_drug_name),
            value = active.drugName,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))

        DetailField(
            label = stringResource(R.string.batch_stock_count_label_ndc),
            value = active.ndc,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            DetailField(
                label = stringResource(R.string.batch_stock_count_label_batch_no),
                value = active.batchNo,
                modifier = Modifier.weight(1f),
            )
            DetailField(
                label = stringResource(R.string.batch_stock_count_label_expiry),
                value = active.expiry,
                modifier = Modifier.weight(1f),
            )
            DetailField(
                label = stringResource(R.string.batch_stock_count_label_bucket),
                value = active.bucket,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(14.dp))

        CounterRow(
            count = active.bottles,
            totalPills = active.totalPills,
            onIncrement = onIncrement,
            onDecrement = onDecrement,
            // Light grey fill so the +/- tiles read as filled buttons against the
            // white portrait card (matches Figma).
            tileFill = Color(0xFFF2F2F2),
            // Compact proportions so the counter sits tight against the fields
            // rather than dominating the card (matches Figma).
            compact = true,
        )
        Spacer(modifier = Modifier.height(14.dp))

        // CANCEL / ADD: compact, centered with a gap, NOT stretched to fill the
        // row (matches Figma — the buttons hug their labels rather than splitting
        // the full width).
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            Box(modifier = Modifier.width(120.dp)) {
                HollowButton(
                    text = stringResource(R.string.batch_stock_count_cancel),
                    onClick = onClear,
                    color = MaterialTheme.colorScheme.primary,
                    fixedWidth = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(modifier = Modifier.width(120.dp)) {
                ActionButtonPrimary(
                    text = stringResource(R.string.batch_stock_count_add),
                    onClick = onAdd,
                    color = MaterialTheme.colorScheme.primary,
                    fixedWidth = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/* ─────────────────────────  PHONE SCANNED-DRUG CARD  ───────────────────────── */

/**
 * Compact phone layout of the active scanned-NDC details, matching the phone
 * Figma exactly (two fields per row so the whole card + counter + buttons fit
 * within the collapsed sheet peek — the full-width-per-field portrait variant
 * was too tall and pushed CANCEL/ADD below the fold):
 *  - Drug Name (2x) | Bucket (1x)
 *  - NDC Number (1.2x) | Batch No. | Expiry Date
 *  - compact +/- counter (grey tiles)
 *  - CANCEL / ADD (full-width split)
 *
 * Renders inside the caller's padded card surface, so it adds none of its own.
 */
@Composable
internal fun ScannedDrugDetailsPhone(
    active: ActiveNdc,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onClear: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.batch_stock_count_scanned_drug_details),
            color = Color(0xFF888888),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(12.dp))

        // Row 1: Drug Name (2x) | Bucket (1x).
        Row(modifier = Modifier.fillMaxWidth()) {
            DetailField(
                label = stringResource(R.string.batch_stock_count_label_drug_name),
                value = active.drugName,
                modifier = Modifier.weight(2f),
            )
            DetailField(
                label = stringResource(R.string.batch_stock_count_label_bucket),
                value = active.bucket,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        // Row 2: NDC (1.2x) | Batch No. | Expiry Date.
        Row(modifier = Modifier.fillMaxWidth()) {
            DetailField(
                label = stringResource(R.string.batch_stock_count_label_ndc),
                value = active.ndc,
                modifier = Modifier.weight(1.2f),
            )
            DetailField(
                label = stringResource(R.string.batch_stock_count_label_batch_no),
                value = active.batchNo,
                modifier = Modifier.weight(1f),
            )
            DetailField(
                label = stringResource(R.string.batch_stock_count_label_expiry),
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
            tileFill = Color(0xFFF2F2F2),
            compact = true,
        )
        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                HollowButton(
                    text = stringResource(R.string.batch_stock_count_cancel),
                    onClick = onClear,
                    color = MaterialTheme.colorScheme.primary,
                    fixedWidth = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                ActionButtonPrimary(
                    text = stringResource(R.string.batch_stock_count_add),
                    onClick = onAdd,
                    color = MaterialTheme.colorScheme.primary,
                    fixedWidth = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/* ─────────────────────────  SUMMARY CARD  ───────────────────────── */

/**
 * Bare scanned-summary row (Total NDCs | Total Pills | END COUNT) without the
 * "SCANNED SUMMARY" header — used by the portrait empty-state card which renders
 * its own placeholder above this row. Identical wiring to [ScannedSummaryCard].
 */
@Composable
internal fun ScannedSummaryRow(
    totalNdcs: Int,
    totalPills: Int,
    onEndCount: () -> Unit,
    modifier: Modifier = Modifier,
    endCountEnabled: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.batch_stock_count_scanned_summary),
            color = Color(0xFF888888),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Stats take their natural width on the left; the END COUNT pill gets
            // the remaining room and a min width so its label never wraps.
            SummaryStat(label = stringResource(R.string.batch_stock_count_total_ndcs), value = totalNdcs.toString(), modifier = Modifier.weight(1f))
            SummaryStat(label = stringResource(R.string.batch_stock_count_total_pills), value = totalPills.toString(), modifier = Modifier.weight(1f))
            ActionButtonPrimary(
                text = stringResource(R.string.batch_stock_count_end_count),
                onClick = onEndCount,
                color = MaterialTheme.colorScheme.primary,
                enabled = endCountEnabled,
                fixedWidth = false,
                modifier = Modifier.widthIn(min = 120.dp),
            )
        }
    }
}

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
            text = stringResource(R.string.batch_stock_count_scanned_summary),
            color = Color(0xFF888888),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SummaryStat(label = stringResource(R.string.batch_stock_count_total_ndcs), value = totalNdcs.toString(), modifier = Modifier.weight(1f))
            SummaryStat(label = stringResource(R.string.batch_stock_count_total_pills), value = totalPills.toString(), modifier = Modifier.weight(1f))
            Box(modifier = Modifier.width(130.dp)) {
                ActionButtonPrimary(
                    text = stringResource(R.string.batch_stock_count_end_count),
                    onClick = onEndCount,
                    color = MaterialTheme.colorScheme.primary,
                    fixedWidth = false,
                    modifier = Modifier.fillMaxWidth(),
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
