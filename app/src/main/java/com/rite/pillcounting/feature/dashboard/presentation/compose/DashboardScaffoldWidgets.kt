package com.rite.pillcounting.feature.dashboard.presentation.compose

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.PriorityHigh
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Task
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.rite.pillcounting.R
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.MenuButton
import com.rite.pillcounting.feature.dashboard.domain.model.DashboardTab
import com.rite.pillcounting.feature.dashboard.domain.model.DashboardUiState
import com.rite.pillcounting.feature.dashboard.domain.model.KpiFilter
import com.rite.pillcounting.feature.dashboard.domain.model.QueueItem
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Shared scaffold widgets for the new dashboard. Both DashboardTabletPortrait and
// DashboardTabletLandscape (and eventually the phone variants) consume these so the
// "same data, different placement" contract holds without per-variant duplication.

internal fun buildTerminalUserLine(uiState: DashboardUiState): String {
    val profile = uiState.userDetail?.profile
    val activeTerminalName = uiState.userDetail?.terminals
        ?.firstOrNull { it.isActive == true }
        ?.terminalName
        ?.takeIf { it.isNotBlank() }
    val terminal = activeTerminalName?.let { "Terminal $it" }
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
    val logoSize = if (compact) 40.dp else 80.dp
    val horizontalPadding = if (compact) 12.dp else 16.dp
    val verticalPadding = if (compact) 8.dp else 12.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = "Pill Counter",
            modifier = Modifier.size(logoSize),
        )
        Spacer(modifier = Modifier.width(if (compact) 8.dp else 4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = pharmacyName ?: "—",
                style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                color = DashboardPrimaryText,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                text = terminalAndUserLine,
                style = MaterialTheme.typography.bodySmall,
                color = DashboardSubtleText,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
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
                    text = if (isPmsConnected) "PMS Connected" else "PMS Disconnected",
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
) {
    Card(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
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
            )
            Spacer(modifier = Modifier.width(if (compact) 14.dp else 20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = if (compact) 20.sp else 28.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                    color = DashboardSubtleText,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal data class KpiCardSpec(
    val filter: KpiFilter,
    val lineOne: String,
    val lineTwo: String,
    val icon: ImageVector,
)

internal val DefaultKpiCards: List<KpiCardSpec> = listOf(
    KpiCardSpec(KpiFilter.DISP_HIGH_PRIORITY, "Disp.", "High Priority", Icons.Outlined.PriorityHigh),
    KpiCardSpec(KpiFilter.DISP_PENDING, "Disp.", "Pending", Icons.Outlined.Refresh),
    KpiCardSpec(KpiFilter.DISP_CONTROLLED, "Disp.", "Cont. Drugs", Icons.Outlined.Shield),
    KpiCardSpec(KpiFilter.DISP_HAZARDOUS, "Disp.", "Hazardous", Icons.Outlined.Warning),
    KpiCardSpec(KpiFilter.INV_CYCLE_COUNT, "Inv.", "Cycle Count", Icons.Outlined.Task),
    KpiCardSpec(KpiFilter.INV_PENDING_BATCH, "Inv.", "Pending Batch", Icons.Outlined.Inventory2),
)

@Composable
internal fun ScaffoldKpiRow(
    counts: Map<KpiFilter, Int>,
    activeFilter: KpiFilter?,
    onTap: (KpiFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DefaultKpiCards.forEach { spec ->
            ScaffoldKpiCard(
                count = counts[spec.filter] ?: 0,
                lineOne = spec.lineOne,
                lineTwo = spec.lineTwo,
                icon = spec.icon,
                isActive = activeFilter == spec.filter,
                onClick = { onTap(spec.filter) },
                modifier = Modifier.weight(1f),
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
) {
    // Each card claims an equal vertical share. This bounds the height every card sees,
    // which is required because ScaffoldKpiCard's inner Box uses fillMaxSize() — without a
    // bounded height the first card consumes the column and the rest get pushed off-screen.
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DefaultKpiCards.forEach { spec ->
            ScaffoldKpiCard(
                count = counts[spec.filter] ?: 0,
                lineOne = spec.lineOne,
                lineTwo = spec.lineTwo,
                icon = spec.icon,
                isActive = activeFilter == spec.filter,
                onClick = { onTap(spec.filter) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
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
) {
    val scrollState = androidx.compose.foundation.rememberScrollState()
    Column(
        modifier = modifier.verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DefaultKpiCards.forEach { spec ->
            ScaffoldKpiCard(
                count = counts[spec.filter] ?: 0,
                lineOne = spec.lineOne,
                lineTwo = spec.lineTwo,
                icon = spec.icon,
                isActive = activeFilter == spec.filter,
                onClick = { onTap(spec.filter) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
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
) {
    val scrollState = androidx.compose.foundation.rememberScrollState()
    Row(
        modifier = modifier.horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DefaultKpiCards.forEach { spec ->
            ScaffoldKpiCard(
                count = counts[spec.filter] ?: 0,
                lineOne = spec.lineOne,
                lineTwo = spec.lineTwo,
                icon = spec.icon,
                isActive = activeFilter == spec.filter,
                onClick = { onTap(spec.filter) },
                modifier = Modifier
                    .width(108.dp)
                    .height(96.dp),
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
    icon: ImageVector,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    singleLabelLine: Boolean = false,
) {
    val scale by animateFloatAsState(targetValue = if (isActive) 1.02f else 1f, label = "kpiScale")
    Card(
        modifier = modifier
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .clickable { onClick() },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isActive) 8.dp else 0.dp),
        border = if (isActive) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(16.dp),
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
                        color = DashboardSubtleText,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        text = lineOne,
                        style = MaterialTheme.typography.bodySmall,
                        color = DashboardSubtleText,
                    )
                    Text(
                        text = lineTwo,
                        style = MaterialTheme.typography.bodySmall,
                        color = DashboardSubtleText,
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
            label = "TODAY'S QUEUE",
            isActive = activeTab == DashboardTab.TODAYS_QUEUE,
            onClick = { onSelect(DashboardTab.TODAYS_QUEUE) },
            modifier = Modifier.weight(1f),
        )
        ScaffoldTab(
            label = "RECENT ACTIVITY",
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
            style = MaterialTheme.typography.labelMedium,
            color = if (isActive) MaterialTheme.colorScheme.secondary else DashboardSubtleText,
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
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Nothing here yet",
                style = MaterialTheme.typography.bodyMedium,
                color = DashboardSubtleText,
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(items, key = { item ->
            when (item) {
                is QueueItem.Dispense -> "d-${item.txn.txnId}"
                is QueueItem.Inventory -> "i-${item.batch.batchId}"
            }
        }) { item ->
            when (item) {
                is QueueItem.Dispense -> ScaffoldDispenseRow(item, onDispenseClick)
                is QueueItem.Inventory -> ScaffoldInventoryRow(item, onInventoryClick)
            }
        }
    }
}

@Composable
private fun ScaffoldDispenseRow(item: QueueItem.Dispense, onClick: ((Long) -> Unit)?) {
    val interactionSource = remember { MutableInteractionSource() }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                    ) { onClick(item.txn.txnId) }
                } else Modifier
            ),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 72.dp, height = 56.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFF0F0F0)),
                contentAlignment = Alignment.Center,
            ) {
                val path = item.txn.barcodeImage
                if (!path.isNullOrEmpty()) {
                    Image(
                        painter = rememberAsyncImagePainter(
                            ImageRequest.Builder(LocalContext.current)
                                .data(File(path))
                                .size(216, 168) // 3x the 72x56dp display box; Coil downsamples on decode
                                .placeholder(R.drawable.prescription_icon)
                                .error(R.drawable.prescription_icon)
                                .build()
                        ),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        painter = painterResource(id = R.drawable.prescription_icon),
                        contentDescription = null,
                        tint = DashboardSubtleText,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "NDC ${item.txn.ndc ?: "—"}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = item.txn.drugName ?: "—",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DashboardPrimaryText,
                )
                Row {
                    Text(
                        text = formatDate(item.txn.createdAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = DashboardSubtleText,
                    )
                    if (item.is340B) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "340B",
                            style = MaterialTheme.typography.bodySmall,
                            color = DashboardSubtleText,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            val target = item.txn.targetCount ?: 0
            val have = item.txn.totalPillCount
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (target > 0) {
                    ProgressPie(
                        progress = (have.toFloat() / target.toFloat()).coerceIn(0f, 1f),
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                Text(
                    text = if (target > 0) "$have/$target" else "$have",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DashboardPrimaryText,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * Tiny filled pie indicator showing `progress` (0..1) of a target. Empty arc is rendered as a
 * light grey ring so 0% reads as a hollow circle and 100% as a fully filled disk.
 */
@Composable
private fun ProgressPie(
    progress: Float,
    color: Color,
    size: androidx.compose.ui.unit.Dp = 24.dp,
) {
    androidx.compose.foundation.Canvas(modifier = Modifier.size(size)) {
        // Empty backdrop ring so the shape is visible at 0%.
        drawCircle(color = Color(0xFFE0E0E0))
        if (progress > 0f) {
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = true,
            )
        }
    }
}

@Composable
private fun ScaffoldInventoryRow(item: QueueItem.Inventory, onClick: ((Long) -> Unit)?) {
    val interactionSource = remember { MutableInteractionSource() }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                    ) { onClick(item.batch.batchId) }
                } else Modifier
            ),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 72.dp, height = 56.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFF0F0F0)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.stock),
                    contentDescription = null,
                    tint = DashboardSubtleText,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = item.batch.batchId.toString(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.SemiBold,
                )
                Row {
                    Text(
                        text = formatDate(item.batch.createdAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = DashboardSubtleText,
                    )
                    if (item.is340B) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "340B",
                            style = MaterialTheme.typography.bodySmall,
                            color = DashboardSubtleText,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            Text(
                text = "${item.batch.uniqueNdcCount} NDCs",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun QuickActionRingIcon(
    @DrawableRes innerIconRes: Int,
    contentDescription: String?,
    compact: Boolean = false,
) {
    val ringSize = if (compact) 64.dp else 110.dp
    val iconSize = if (compact) 32.dp else 56.dp
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

// Single-mode (light) palette per HOMESCREEN_REDESIGN.md.
internal val DashboardPageBackground = Color(0xFFF2F3F5)
internal val DashboardPrimaryText = Color(0xFF1F1F1F)
internal val DashboardSubtleText = Color(0xFF8A8A8A)
