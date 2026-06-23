package com.rite.pillcounting.feature.inventoryFlow.presentation.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rite.pillcounting.R
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.ActionButtonPrimary
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.HollowButton
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveDp
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveSp
import com.rite.pillcounting.feature.inventoryFlow.domain.model.EditBatchRow
import com.rite.pillcounting.feature.inventoryFlow.domain.model.EditDrugDetails
import com.rite.pillcounting.ui.theme.AppTheme

/* ─────────────────────────  EDIT DETAILS CONTENT  ───────────────────────── */

/**
 * In-place "Edit Details" editor rendered inside the scanned-drug card surface
 * (the right panel in landscape, the details card in portrait) — it replaces the
 * scanned-drug counter content while editing rather than floating as a separate
 * dialog. The caller supplies the card background + padding; this fills it.
 *
 * Holds a local working copy of the sealed / open rows so +/- and delete edits
 * are live but only committed on SAVE; CANCEL/close drops them.
 */
@Composable
internal fun EditDetailsContent(
    details: EditDrugDetails,
    onDismiss: () -> Unit,
    onSave: (sealed: List<EditBatchRow>, open: List<EditBatchRow>) -> Unit,
    // When the surrounding surface already shows an "Edit Details" header + close
    // (e.g. the landscape panel header morphs on edit), the internal title bar is
    // suppressed to avoid showing it twice.
    showTitle: Boolean = true,
    // On a wide full-width card (tablet portrait) the scanned-drug details fit on a
    // single Drug Name | NDC Number | Bucket row (per Figma). On the narrower
    // landscape card they stack: Drug Name full-width, then NDC | Bucket.
    wideDrugDetails: Boolean = false,
) {
    // Re-seeded whenever a new drug's details are loaded.
    val sealed = remember(details) { mutableStateListOf<EditBatchRow>().apply { addAll(details.sealedBottles) } }
    val open = remember(details) { mutableStateListOf<EditBatchRow>().apply { addAll(details.openPills) } }

    Column(modifier = Modifier.fillMaxSize()) {
        // Title bar.
        if (showTitle) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.batch_stock_count_edit_details),
                    color = AppTheme.extendedColors.textColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.batch_stock_count_cancel),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(22.dp)
                        .clickable(onClick = onDismiss),
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
        }

        // Scrollable body so long batch lists don't push CANCEL/SAVE off-screen.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(R.string.batch_stock_count_scanned_drug_details),
                color = AppTheme.extendedColors.textColor.copy(alpha = 0.7f),
                fontSize = responsiveSp(8.sp),
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (wideDrugDetails) {
                // Single row: Drug Name | NDC Number | Bucket (matches portrait Figma).
                Row(modifier = Modifier.fillMaxWidth()) {
                    EditDetailField(
                        label = stringResource(R.string.batch_stock_count_label_drug_name),
                        value = details.drugName,
                        modifier = Modifier.weight(2f),
                    )
                    EditDetailField(
                        label = stringResource(R.string.batch_stock_count_label_ndc),
                        value = details.ndc,
                        modifier = Modifier.weight(1.4f),
                    )
                    EditDetailField(
                        label = stringResource(R.string.batch_stock_count_label_bucket),
                        value = details.bucket,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                // Drug Name on its own full-width row, then NDC | Bucket.
                EditDetailField(
                    label = stringResource(R.string.batch_stock_count_label_drug_name),
                    value = details.drugName,
                    modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider(
                    color = AppTheme.extendedColors.primaryBackground,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    EditDetailField(
                        label = stringResource(R.string.batch_stock_count_label_ndc),
                        value = details.ndc,
                        modifier = Modifier.weight(1f),
                    )
                    EditDetailField(
                        label = stringResource(R.string.batch_stock_count_label_bucket),
                        value = details.bucket,
                        modifier = Modifier.weight(1f),
                        alignEnd = true,
                    )
                }
            }
            HorizontalDivider(
                color = AppTheme.extendedColors.primaryBackground,
                modifier = Modifier.padding(vertical = 12.dp),
            )

            // Sealed bottles section.
            EditSectionHeader(
                title = stringResource(R.string.batch_stock_count_sealed_bottles),
                total = sealed.sumOf { it.qty },
            )
            if (sealed.isEmpty()) {
                EditEmptyRow()
            } else {
                EditBatchColumnHeader()
                sealed.forEachIndexed { index, row ->
                    EditBatchRowItem(
                        row = row,
                        onIncrement = { sealed[index] = row.copy(qty = row.qty + 1) },
                        onDecrement = { sealed[index] = row.copy(qty = (row.qty - 1).coerceAtLeast(0)) },
                        onDelete = { sealed.remove(row) },
                    )
                }
            }
            HorizontalDivider(
                color = AppTheme.extendedColors.primaryBackground,
                modifier = Modifier.padding(vertical = 16.dp),
            )

            // Open pills section.
            EditSectionHeader(
                title = stringResource(R.string.batch_stock_count_open_pills),
                total = open.sumOf { it.qty },
            )
            if (open.isEmpty()) {
                EditEmptyRow()
            } else {
                EditBatchColumnHeader()
                open.forEachIndexed { index, row ->
                    EditBatchRowItem(
                        row = row,
                        onIncrement = { open[index] = row.copy(qty = row.qty + 1) },
                        onDecrement = { open[index] = row.copy(qty = (row.qty - 1).coerceAtLeast(0)) },
                        onDelete = { open.remove(row) },
                    )
                }
            }
        }

        HorizontalDivider(
            color = AppTheme.extendedColors.primaryBackground,
            modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
        )

        // CANCEL / SAVE — compact, centered.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            Box(modifier = Modifier.width(140.dp)) {
                HollowButton(
                    text = stringResource(R.string.batch_stock_count_cancel),
                    onClick = onDismiss,
                    color = MaterialTheme.colorScheme.primary,
                    fixedWidth = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(modifier = Modifier.width(140.dp)) {
                ActionButtonPrimary(
                    text = stringResource(R.string.batch_stock_count_save),
                    onClick = { onSave(sealed.toList(), open.toList()) },
                    color = MaterialTheme.colorScheme.primary,
                    fixedWidth = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Section header: bold magenta title on the left, magenta total on the right. */
@Composable
private fun EditSectionHeader(title: String, total: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.secondary,
            fontSize = responsiveSp(8.sp),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = total.toString(),
            color = MaterialTheme.colorScheme.secondary,
            fontSize = responsiveSp(8.sp),
            fontWeight = FontWeight.SemiBold,
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
}

/** Small grey "Batch No. / Expiry Date" column captions above the rows. */
@Composable
private fun EditBatchColumnHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
    ) {
        Text(
            text = stringResource(R.string.batch_stock_count_label_batch_no),
            color = AppTheme.extendedColors.textColor.copy(alpha = 0.6f),
            fontSize = responsiveSp(7.sp),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.batch_stock_count_label_expiry),
            color = AppTheme.extendedColors.textColor.copy(alpha = 0.6f),
            fontSize = responsiveSp(7.sp),
            modifier = Modifier.weight(1f),
        )
        // Spacer matching the stepper + delete column on the data rows.
        Spacer(modifier = Modifier.width(responsiveDp(100.dp) + responsiveDp(25.dp) + 10.dp))
    }
}

/** One editable batch row: batch no | expiry | stepper | delete. */
@Composable
private fun EditBatchRowItem(
    row: EditBatchRow,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.batchNo.ifBlank { "—" },
            color = AppTheme.extendedColors.textColor,
            fontSize = responsiveSp(6.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = row.expiry.ifBlank { "—" },
            color = AppTheme.extendedColors.textColor,
            fontSize = responsiveSp(6.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        MiniStepper(value = row.qty, onMinus = onDecrement, onPlus = onIncrement)
        Spacer(modifier = Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(responsiveDp(25.dp))
                .clickable(onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(id = R.drawable.delete),
                contentDescription = stringResource(R.string.batch_stock_count_delete_row),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(responsiveDp(15.dp)),
            )
        }
    }
}

/**
 * [- value +] stepper matching Figma: a single light-grey rounded container with
 * plain minus/plus icons on the sides and a white rounded box in the middle
 * holding the magenta value.
 */
@Composable
private fun MiniStepper(value: Int, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        modifier = Modifier
            .width(responsiveDp(100.dp))
            .height(responsiveDp(32.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(AppTheme.extendedColors.primaryBackground)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(responsiveDp(25.dp))
                .clickable(onClick = onMinus),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Remove,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(responsiveDp(16.dp)),
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(9.dp))
                .background(AppTheme.extendedColors.secondaryBackground),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = value.toString(),
                color = MaterialTheme.colorScheme.secondary,
                fontSize = responsiveSp(8.sp),
                fontWeight = FontWeight.SemiBold,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(responsiveDp(25.dp))
                .clickable(onClick = onPlus),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(responsiveDp(16.dp)),
            )
        }
    }
}

/** Placeholder shown when a section has no batch rows. */
@Composable
private fun EditEmptyRow() {
    Text(
        text = "—",
        color = AppTheme.extendedColors.textColor.copy(alpha = 0.5f),
        fontSize = 13.sp,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

/** Label-over-value detail field (matches the scanned-drug card styling). */
@Composable
private fun EditDetailField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        Text(
            text = label,
            color = AppTheme.extendedColors.textColor.copy(alpha = 0.7f),
            fontSize = responsiveSp(7.sp),
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            color = MaterialTheme.colorScheme.secondary,
            fontSize = responsiveSp(8.sp),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val DELETE_BOX = 36.dp
