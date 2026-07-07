package com.rite.pillcounting.feature.dashboard.presentation.compose

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.rite.pillcounting.R
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.MenuButton
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveDp
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveSp
import com.rite.pillcounting.core.utils.compose.DrugCountRow
import com.rite.pillcounting.core.utils.compose.DrugCountRowData
import com.rite.pillcounting.feature.dashboard.domain.model.DashboardTab
import com.rite.pillcounting.feature.dashboard.domain.model.DashboardUiState
import com.rite.pillcounting.feature.dashboard.domain.model.KpiFilter
import com.rite.pillcounting.feature.dashboard.domain.model.QueueItem
import com.rite.pillcounting.feature.dashboard.presentation.model.DefaultKpiCards
import com.rite.pillcounting.feature.history.presentation.compose.BatchHistoryRow
import com.rite.pillcounting.ui.theme.AppTheme.extendedColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Shared scaffold widgets for the new dashboard. Both DashboardTabletPortrait and
// DashboardTabletLandscape (and eventually the phone variants) consume these so the
// "same data, different placement" contract holds without per-variant duplication.

internal fun buildTerminalUserLine(
    uiState: DashboardUiState,
    includeTerminal: Boolean = true,
): String {
    val profile = uiState.userDetail?.profile
    val activeTerminalName = uiState.userDetail?.settings?.terminals
        ?.firstOrNull { it.isActive == true }
        ?.terminalName
        ?.takeIf { it.isNotBlank() }
    // Terminal is HL7/PMS-driven — omit it from the top bar when HL7 is disabled.
    val terminal = if (includeTerminal) activeTerminalName else null
    val user = listOfNotNull(profile?.fName, profile?.lName).joinToString(" ").ifBlank { null }
    return listOfNotNull(terminal, user).joinToString(" | ").ifBlank { "—" }
}

@Composable
internal fun ScaffoldTopBar(
    pharmacyName: String?,
    terminalAndUserLine: String,
    showPmsDot: Boolean,
    isPmsConnected: Boolean,
    navController: NavController,
    compact: Boolean = false,
) {
    val logoSize = if (compact) 30.dp else 40.dp
    val horizontalPadding = if (compact) 12.dp else 16.dp
    val verticalPadding = if (compact) 8.dp else 12.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding)
            .padding(top = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(id = R.drawable.redsaillogo),
            contentDescription = stringResource(R.string.pill_count_app_title),
            modifier = Modifier.size(logoSize),
        )
        Spacer(modifier = Modifier.width(if (compact) 8.dp else 14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = pharmacyName ?: "—",
                style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                color = extendedColors.textColor,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = terminalAndUserLine,
                style = MaterialTheme.typography.bodySmall,
                color = extendedColors.textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showPmsDot) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = if (isPmsConnected) MaterialTheme.colorScheme.secondary else Color.Gray,
                            shape = RoundedCornerShape(50),
                        )
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(
                        if (isPmsConnected) R.string.pms_status_connected else R.string.pms_status_disconnected
                    ),
                    style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                )
            }
            Spacer(modifier = Modifier.width(if (compact) 8.dp else 12.dp))
        }
        MenuButton(navController = navController)
    }
}

@Composable
internal fun ScaffoldQuickActionCard(
    title: String,
    subtitle: String,
    @DrawableRes innerIconRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    centered: Boolean = false,
    ringSize: Dp? = null,
    iconSize: Dp? = null,
) {
    val cardShape = RoundedCornerShape(12.dp)
    Card(
        modifier = modifier
            .clickable { onClick() },
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = extendedColors.secondaryBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        if (centered) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(if (compact) 16.dp else 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                QuickActionRingIcon(
                    innerIconRes = innerIconRes,
                    contentDescription = title,
                    compact = compact,
                    overrideRingSize = ringSize,
                    overrideIconSize = iconSize,
                )
                Spacer(modifier = Modifier.height(if (compact) 12.dp else 20.dp))
                Text(
                    text = title,
                    fontSize = if (compact) 20.sp else 28.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                    color = extendedColors.textColor,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(if (compact) 16.dp else 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QuickActionRingIcon(
                    innerIconRes = innerIconRes,
                    contentDescription = title,
                    compact = compact,
                    overrideRingSize = ringSize,
                    overrideIconSize = iconSize,
                )
                Spacer(modifier = Modifier.width(if (compact) 14.dp else 20.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontSize = if (compact) 20.sp else 28.sp,
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                        color = extendedColors.textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}


@Composable
internal fun ScaffoldKpiRow(
    counts: Map<KpiFilter, Int>,
    activeFilter: KpiFilter?,
    onTap: (KpiFilter) -> Unit,
    modifier: Modifier = Modifier,
    cardWidth: Dp? = null,
    cardHeight: Dp? = null,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DefaultKpiCards.forEach { spec ->
            var cardModifier = Modifier.weight(1f)
            if (cardWidth != null) cardModifier = cardModifier.width(cardWidth)
            if (cardHeight != null) cardModifier = cardModifier.height(cardHeight)
            ScaffoldKpiCard(
                count = counts[spec.filter] ?: 0,
                lineOne = stringResource(spec.lineOneRes),
                lineTwo = stringResource(spec.lineTwoRes),
                iconRes = spec.iconRes,
                isActive = activeFilter == spec.filter,
                onClick = { onTap(spec.filter) },
                modifier = cardModifier,
            )
        }
    }
}

/**
 * Vertical stack of all 6 KPI cards. Used by Tablet Landscape, where the KPIs sit in a narrow
 * middle column between Quick Actions and the queue list. Each card takes equal vertical weight.
 */
@Composable
internal fun ScaffoldKpiColumn(
    counts: Map<KpiFilter, Int>,
    activeFilter: KpiFilter?,
    onTap: (KpiFilter) -> Unit,
    modifier: Modifier = Modifier,
    cardWidth: Dp? = null,
    cardHeight: Dp? = null,
) {
    // Each card claims an equal vertical share. This bounds the height every card sees,
    // which is required because ScaffoldKpiCard's inner Box uses fillMaxSize() — without a
    // bounded height the first card consumes the column and the rest get pushed off-screen.
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DefaultKpiCards.forEach { spec ->
            var cardModifier: Modifier = if (cardWidth != null) Modifier.width(cardWidth) else Modifier.fillMaxWidth()
            cardModifier = if (cardHeight != null) cardModifier.height(cardHeight) else cardModifier.weight(1f)
            ScaffoldKpiCard(
                count = counts[spec.filter] ?: 0,
                lineOne = stringResource(spec.lineOneRes),
                lineTwo = stringResource(spec.lineTwoRes),
                iconRes = spec.iconRes,
                isActive = activeFilter == spec.filter,
                onClick = { onTap(spec.filter) },
                modifier = cardModifier,
            )
        }
    }
}

/**
 * Vertically scrollable single-column KPI strip. Used by phone landscape where vertical space
 * is constrained but the middle column is narrow — user scrolls vertically through the 6 cards.
 * Cards have a fixed height; the column scrolls.
 */
@Composable
internal fun ScaffoldKpiScrollColumn(
    counts: Map<KpiFilter, Int>,
    activeFilter: KpiFilter?,
    onTap: (KpiFilter) -> Unit,
    modifier: Modifier = Modifier,
    cardWidth: Dp? = null,
    cardHeight: Dp = 72.dp,
) {
    val scrollState = androidx.compose.foundation.rememberScrollState()
    Column(
        modifier = modifier.verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DefaultKpiCards.forEach { spec ->
            var cardModifier: Modifier = if (cardWidth != null) Modifier.width(cardWidth) else Modifier.fillMaxWidth()
            cardModifier = cardModifier.height(cardHeight)
            ScaffoldKpiCard(
                count = counts[spec.filter] ?: 0,
                lineOne = stringResource(spec.lineOneRes),
                lineTwo = stringResource(spec.lineTwoRes),
                iconRes = spec.iconRes,
                isActive = activeFilter == spec.filter,
                onClick = { onTap(spec.filter) },
                modifier = cardModifier,
                singleLabelLine = true,
            )
        }
    }
}

/**
 * Horizontally scrollable single-row KPI strip. Used by phone portrait where the 6 cards can't
 * fit side-by-side at narrow widths — user scrolls horizontally to reach the trailing cards.
 * Cards have a fixed width so each is fully readable; the row scrolls.
 */
@Composable
internal fun ScaffoldKpiScrollRow(
    counts: Map<KpiFilter, Int>,
    activeFilter: KpiFilter?,
    onTap: (KpiFilter) -> Unit,
    modifier: Modifier = Modifier,
    cardWidth: Dp = 108.dp,
    cardHeight: Dp = 96.dp,
) {
    val scrollState = androidx.compose.foundation.rememberScrollState()
    Row(
        modifier = modifier.horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DefaultKpiCards.forEach { spec ->
            ScaffoldKpiCard(
                count = counts[spec.filter] ?: 0,
                lineOne = stringResource(spec.lineOneRes),
                lineTwo = stringResource(spec.lineTwoRes),
                iconRes = spec.iconRes,
                isActive = activeFilter == spec.filter,
                onClick = { onTap(spec.filter) },
                modifier = Modifier
                    .width(cardWidth)
                    .height(cardHeight),
                singleLabelLine = true,
            )
        }
    }
}

@Composable
internal fun ScaffoldKpiCard(
    count: Int,
    lineOne: String,
    lineTwo: String,
    @DrawableRes iconRes: Int,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    singleLabelLine: Boolean = false
) {
    // Selection is shown by a primary border plus an all-around primary-tinted
    // shadow, both strictly keyed on isActive so deselecting fully reverts.
    // Background fill stays the same for selected and unselected cards.
    val shape = RoundedCornerShape(8.dp)
    Card(
        modifier = modifier
            .then(
                if (isActive) {
                    Modifier.shadow(
                        elevation = 6.dp,
                        shape = shape,
                        ambientColor = MaterialTheme.colorScheme.primary,
                        spotColor = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Modifier
                },
            )
            .clickable { onClick() },
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = extendedColors.secondaryBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = if (isActive) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(responsiveDp(14.dp)),
            )
            Column(modifier = Modifier.align(Alignment.BottomStart)) {
                Text(
                    text = count.toString(),
                    fontSize = 28.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                if (singleLabelLine) {
                    Text(
                        text = lineTwo,
                        style = MaterialTheme.typography.bodySmall,
                        color = extendedColors.textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        text = lineOne,
                        style = MaterialTheme.typography.bodySmall,
                        color = extendedColors.textColor,
                    )
                    Text(
                        text = lineTwo,
                        style = MaterialTheme.typography.bodySmall,
                        color = extendedColors.textColor,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ScaffoldTabStrip(
    activeTab: DashboardTab,
    onSelect: (DashboardTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        ScaffoldTab(
            label = stringResource(R.string.todays_queue),
            isActive = activeTab == DashboardTab.TODAYS_QUEUE,
            onClick = { onSelect(DashboardTab.TODAYS_QUEUE) },
            modifier = Modifier.weight(1f),
        )
        ScaffoldTab(
            label = stringResource(R.string.recent_activity),
            isActive = activeTab == DashboardTab.RECENT_ACTIVITY,
            onClick = { onSelect(DashboardTab.RECENT_ACTIVITY) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ScaffoldTab(
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            fontSize = responsiveSp(8.sp),
            color = if (isActive) MaterialTheme.colorScheme.secondary else extendedColors.textColor,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .height(2.dp)
                .fillMaxWidth()
                .background(if (isActive) MaterialTheme.colorScheme.secondary else Color.Transparent)
        )
    }
}

@Composable
internal fun ScaffoldQueueList(
    items: List<QueueItem>,
    onDispenseClick: ((Long) -> Unit)?,
    onInventoryClick: ((Long) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.complete),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.all_caught_up),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.no_pending_tasks),
                    style = MaterialTheme.typography.bodyMedium,
                    color = extendedColors.textColor,
                    textAlign = TextAlign.Center,
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(items, key = { item ->
            when (item) {
                is QueueItem.Dispense -> "d-${item.txn.txnId}"
                is QueueItem.Inventory -> "i-${item.batch.batchId}"
            }
        }) { item ->
            when (item) {
                is QueueItem.Dispense -> DrugCountRow(
                    data = DrugCountRowData(
                        barcodeImage = item.txn.barcodeImage,
                        ndc = item.txn.ndc,
                        drugType = item.txn.drugType,
                        drugName = item.txn.drugName ?: "—",
                        date = formatDate(item.txn.createdAt),
                        bucketId = item.txn.bucketId,
                        pillCount = item.txn.totalPillCount,
                        targetCount = item.txn.targetCount ?: 0,
                        countType = item.txn.countType,
                        isComingFromHL7 = item.txn.isComingFromHL7,
                        strength = item.txn.strength,
                        dosageForm = item.txn.dosageForm,
                    ),
                    onClick = { onDispenseClick?.invoke(item.txn.txnId) },
                )

                is QueueItem.Inventory -> BatchHistoryRow(
                    title = item.batch.batchId.toString(),
                    dateTime = formatDate(item.batch.createdAt),
                    bucketId = item.batch.bucketId,
                    count = item.batch.uniqueNdcCount.toString(),
                    isPrescription = item.batch.requestIdFromPMS != null,
                    onBatchClick = { onInventoryClick?.invoke(item.batch.batchId) },
                )
            }
        }
    }
}

@Composable
private fun QuickActionRingIcon(
    @DrawableRes innerIconRes: Int,
    contentDescription: String?,
    compact: Boolean = false,
    overrideRingSize: Dp? = null,
    overrideIconSize: Dp? = null,
) {
    val ringSize = overrideRingSize ?: if (compact) 64.dp else 110.dp
    val iconSize = overrideIconSize ?: if (compact) 32.dp else 56.dp
    Box(
        modifier = Modifier
            .size(ringSize)
            .border(
                width = 2.dp,
                color = MaterialTheme.colorScheme.primary,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(id = innerIconRes),
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(iconSize),
        )
    }
}

private val DATE_FMT = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault())

internal fun formatDate(epochMillis: Long): String = DATE_FMT.format(Date(epochMillis))
