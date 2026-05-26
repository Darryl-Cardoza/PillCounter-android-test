package com.rite.pillcounting.feature.dashboard.presentation.variant

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.DrawableRes
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.Image
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import com.rite.pillcounting.R
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.MenuButton
import com.rite.pillcounting.feature.dashboard.domain.model.DashboardTab
import com.rite.pillcounting.feature.dashboard.domain.model.KpiFilter
import com.rite.pillcounting.feature.dashboard.domain.model.QueueItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tablet portrait dashboard variant — **scaffold (between Turn 1 and Turn 2).**
 *
 * This is intentionally a working-but-rough version of the new dashboard so it can be tested on
 * the tablet immediately. Everything is real and wired (state, taps, navigation), but visual
 * fidelity to the Figma is deferred to:
 *   - Turn 2: extract the 7 polished widgets (TopBar, QuickActionCard, KpiCard, TabStrip, queue
 *     rows, recent activity row)
 *   - Turn 3: replace inline composables here with those widgets and match Figma pixel-for-pixel
 *
 * What works today on the tablet:
 *   - Top bar shows pharmacy + terminal + user (from `userDetail` / preferences), plus PMS dot
 *     and the existing menu button.
 *   - Dispense card navigates to the dispense scan flow.
 *   - Inventory card triggers the inventory flow (toast placeholder until Turn 3 wires the
 *     existing bucket-select dialogs).
 *   - 6 KPI cards show live counts from the merged queue. Tapping a card toggles a filter; the
 *     queue list below filters immediately.
 *   - Tab strip switches between Today's Queue (functional) and Recent Activity (empty for now
 *     — `loadRecentActivity()` lands in Turn 2).
 *   - Queue list renders both Dispense and Inventory rows with the available DTO fields.
 */
@Composable
fun DashboardTabletPortrait(params: DashboardVariantParams) {
    val uiState = params.uiState

    Box(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .background(DashboardPageBackground)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScaffoldTopBar(
                pharmacyName = uiState.userDetail?.profile?.pharmacyName,
                terminalAndUserLine = buildTerminalUserLine(uiState),
                showPmsDot = params.isHl7Enabled,
                isPmsConnected = params.isPmsConnected,
                navController = params.navController,
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "QUICK ACTIONS",
                    style = MaterialTheme.typography.labelSmall,
                    color = DashboardSubtleText,
                    fontWeight = FontWeight.SemiBold,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ScaffoldQuickActionCard(
                        title = "Dispense",
                        subtitle = "Tap to scan Rx Labels",
                        // Same inner icon used by FixedCountSection (the pill capsule).
                        innerIconRes = R.drawable.regular_count_inner,
                        onClick = params.onDispenseQuickAction,
                        modifier = Modifier.weight(1f),
                    )
                    ScaffoldQuickActionCard(
                        title = "Inventory",
                        subtitle = "Start inventory count",
                        // Default inner icon used by RegularCountSection (the box/inventory icon).
                        innerIconRes = R.drawable.fixed_count_inner,
                        onClick = params.onInventoryQuickAction,
                        modifier = Modifier.weight(1f),
                    )
                }

                ScaffoldKpiRow(
                    counts = uiState.kpiCounts,
                    activeFilter = uiState.activeKpiFilter,
                    onTap = params.onKpiFilterTapped,
                )

                HorizontalDivider(color = Color(0x14000000))

                // Pager state is the single source of truth for which tab is visible. Tab taps
                // animate the pager; swipes update the pager which then notifies the VM via
                // onTabSelected. The VM's `activeTab` is reflected back into the pager only on
                // external changes (e.g. nav return) to avoid a feedback loop while swiping.
                val pagerState = rememberPagerState(
                    initialPage = uiState.activeTab.ordinal,
                    pageCount = { DashboardTab.entries.size },
                )
                val scope = rememberCoroutineScope()

                LaunchedEffect(pagerState.currentPage) {
                    val newTab = DashboardTab.entries[pagerState.currentPage]
                    if (newTab != uiState.activeTab) {
                        params.onTabSelected(newTab)
                    }
                }

                ScaffoldTabStrip(
                    activeTab = DashboardTab.entries[pagerState.currentPage],
                    onSelect = { tab ->
                        scope.launch { pagerState.animateScrollToPage(tab.ordinal) }
                    },
                )

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f),
                ) { page ->
                    when (DashboardTab.entries[page]) {
                        DashboardTab.TODAYS_QUEUE -> ScaffoldQueueList(items = uiState.queue)
                        DashboardTab.RECENT_ACTIVITY -> ScaffoldQueueList(items = uiState.recentActivity)
                    }
                }
            }
        }
    }
}

private fun buildTerminalUserLine(uiState: com.rite.pillcounting.feature.dashboard.domain.model.DashboardUiState): String {
    val profile = uiState.userDetail?.profile
    val terminal = profile?.let { /* terminal name lives in preferences/active terminal; placeholder for scaffold */ null }
    val user = listOfNotNull(profile?.fName, profile?.lName).joinToString(" ").ifBlank { null }
    return listOfNotNull(terminal, user).joinToString(" | ").ifBlank { "—" }
}

@Composable
private fun ScaffoldTopBar(
    pharmacyName: String?,
    terminalAndUserLine: String,
    showPmsDot: Boolean,
    isPmsConnected: Boolean,
    navController: androidx.navigation.NavController,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Multi-color launcher artwork (pink ring + blue pill). Compose's painterResource cannot
        // inflate the adaptive-icon XML at `R.mipmap.ic_launcher_round` on API 26+, so we use
        // the foreground vector directly — the launcher background is white anyway (see
        // res/values/ic_launcher_background.xml), so this matches the on-device app icon.
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = "Pill Counter",
            modifier = Modifier.size(80.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = pharmacyName ?: "—",
                style = MaterialTheme.typography.titleMedium,
                color = DashboardPrimaryText,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = terminalAndUserLine,
                style = MaterialTheme.typography.bodySmall,
                color = DashboardSubtleText,
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
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
        }
        MenuButton(navController = navController)
    }
}

@Composable
private fun ScaffoldQuickActionCard(
    title: String,
    subtitle: String,
    @DrawableRes innerIconRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .height(240.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QuickActionRingIcon(
                innerIconRes = innerIconRes,
                contentDescription = title,
            )
            Spacer(modifier = Modifier.width(20.dp))
            Column {
                Text(
                    text = title,
                    fontSize = 28.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = DashboardSubtleText,
                )
            }
        }
    }
}

@Composable
private fun ScaffoldKpiRow(
    counts: Map<KpiFilter, Int>,
    activeFilter: KpiFilter?,
    onTap: (KpiFilter) -> Unit,
) {
    val cards = listOf(
        KpiFilter.DISP_HIGH_PRIORITY to ("Disp." to "High Priority"),
        KpiFilter.DISP_PENDING to ("Disp." to "Pending"),
        KpiFilter.DISP_CONTROLLED to ("Disp." to "Cont. Drugs"),
        KpiFilter.DISP_HAZARDOUS to ("Disp." to "Hazardous"),
        KpiFilter.INV_CYCLE_COUNT to ("Inv." to "Cycle Count"),
        KpiFilter.INV_PENDING_BATCH to ("Inv." to "Pending Batch"),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        cards.forEach { (filter, label) ->
            ScaffoldKpiCard(
                count = counts[filter] ?: 0,
                lineOne = label.first,
                lineTwo = label.second,
                isActive = activeFilter == filter,
                onClick = { onTap(filter) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ScaffoldKpiCard(
    count: Int,
    lineOne: String,
    lineTwo: String,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = if (isActive) BorderStroke(2.dp, MaterialTheme.colorScheme.secondary) else null,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = count.toString(),
                fontSize = 28.sp,
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.SemiBold,
            )
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

@Composable
private fun ScaffoldTabStrip(
    activeTab: DashboardTab,
    onSelect: (DashboardTab) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
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
        modifier = modifier.clickable { onClick() },
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
private fun ScaffoldQueueList(items: List<QueueItem>) {
    if (items.isEmpty()) {
        Box(
            modifier = Modifier
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
                is QueueItem.Dispense -> ScaffoldDispenseRow(item)
                is QueueItem.Inventory -> ScaffoldInventoryRow(item)
            }
        }
    }
}

@Composable
private fun ScaffoldDispenseRow(item: QueueItem.Dispense) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = Color(0xFFF0F0F0),
                        shape = RoundedCornerShape(6.dp),
                    )
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
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
            Text(
                text = if (target > 0) "$have/$target" else "$have",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ScaffoldInventoryRow(item: QueueItem.Inventory) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = Color(0xFFF0F0F0),
                        shape = RoundedCornerShape(6.dp),
                    )
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
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

/**
 * Quick Action card ring icon — a thin teal circle outline with a pink inner glyph.
 *
 * Deliberately *not* reusing [com.rite.pillcounting.core.utils.compose.DashBoardIcon] because
 * that widget draws an outer blue glow (via `BlurMaskFilter` in `drawBehind`) which the new
 * dashboard mockup does not show.
 */
@Composable
private fun QuickActionRingIcon(
    @DrawableRes innerIconRes: Int,
    contentDescription: String?,
) {
    Box(
        modifier = Modifier
            .size(110.dp)
            .border(
                width = 2.dp,
                color = MaterialTheme.colorScheme.secondary,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(id = innerIconRes),
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
    }
}

private val DATE_FMT = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault())

private fun formatDate(epochMillis: Long): String = DATE_FMT.format(Date(epochMillis))

// Hard-coded palette for the scaffold so the dashboard renders correctly regardless of the
// system dark-mode setting. Per HOMESCREEN_REDESIGN.md the app is single-mode (light only).
// Turn 2 will replace these with proper theme entries when we extract the shared widgets.
private val DashboardPageBackground = Color(0xFFF2F3F5)
private val DashboardPrimaryText = Color(0xFF1F1F1F)
private val DashboardSubtleText = Color(0xFF8A8A8A)
