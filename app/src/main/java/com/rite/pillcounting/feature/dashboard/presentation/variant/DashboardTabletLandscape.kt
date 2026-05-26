package com.rite.pillcounting.feature.dashboard.presentation.variant

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rite.pillcounting.R
import com.rite.pillcounting.feature.dashboard.domain.model.DashboardTab
import com.rite.pillcounting.feature.dashboard.presentation.compose.DashboardPageBackground
import com.rite.pillcounting.feature.dashboard.presentation.compose.DashboardSubtleText
import com.rite.pillcounting.feature.dashboard.presentation.compose.ScaffoldKpiColumn
import com.rite.pillcounting.feature.dashboard.presentation.compose.ScaffoldQueueList
import com.rite.pillcounting.feature.dashboard.presentation.compose.ScaffoldQuickActionCard
import com.rite.pillcounting.feature.dashboard.presentation.compose.ScaffoldTabStrip
import com.rite.pillcounting.feature.dashboard.presentation.compose.ScaffoldTopBar
import com.rite.pillcounting.feature.dashboard.presentation.compose.buildTerminalUserLine
import kotlinx.coroutines.launch

/**
 * Tablet landscape dashboard variant.
 *
 * Same data and callbacks as [DashboardTabletPortrait]; only element placement differs:
 *   - Top bar spans full width (pharmacy info left, PMS dot + menu right).
 *   - Sub-header row aligns "QUICK ACTIONS" label (over the left columns) with the tab strip
 *     (over the queue column on the right).
 *   - Body splits into three side-by-side columns:
 *       1. Quick Actions stacked vertically (Dispense top, Inventory bottom).
 *       2. KPI cards stacked vertically (6 small cards filling the column height).
 *       3. Today's Queue / Recent Activity pager.
 */
@Composable
fun DashboardTabletLandscape(params: DashboardVariantParams) {
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

            // Body — three side-by-side columns under one shared sub-header row.
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

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                // Sub-header: left half holds the section label that captions the Quick Action +
                // KPI columns; right half holds the tab strip that captions the queue column.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "QUICK ACTIONS",
                        style = MaterialTheme.typography.labelSmall,
                        color = DashboardSubtleText,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(0.52f),
                    )
                    ScaffoldTabStrip(
                        activeTab = DashboardTab.entries[pagerState.currentPage],
                        onSelect = { tab ->
                            scope.launch { pagerState.animateScrollToPage(tab.ordinal) }
                        },
                        modifier = Modifier.weight(0.48f),
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Column 1 — Quick Actions stacked vertically.
                    Column(
                        modifier = Modifier
                            .weight(0.30f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ScaffoldQuickActionCard(
                            title = "Dispense",
                            subtitle = "Tap to scan Rx Labels",
                            innerIconRes = R.drawable.regular_count_inner,
                            onClick = params.onDispenseQuickAction,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        )
                        ScaffoldQuickActionCard(
                            title = "Inventory",
                            subtitle = "Start inventory count",
                            innerIconRes = R.drawable.fixed_count_inner,
                            onClick = params.onInventoryQuickAction,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        )
                    }

                    // Column 2 — KPI cards stacked vertically.
                    ScaffoldKpiColumn(
                        counts = uiState.kpiCounts,
                        activeFilter = uiState.activeKpiFilter,
                        onTap = params.onKpiFilterTapped,
                        modifier = Modifier
                            .weight(0.22f)
                            .fillMaxHeight(),
                    )

                    // Column 3 — paged Today's Queue / Recent Activity list.
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .weight(0.48f)
                            .fillMaxHeight(),
                    ) { page ->
                        when (DashboardTab.entries[page]) {
                            DashboardTab.TODAYS_QUEUE -> ScaffoldQueueList(
                                items = uiState.queue,
                                onDispenseClick = null,
                                onInventoryClick = null,
                            )
                            DashboardTab.RECENT_ACTIVITY -> ScaffoldQueueList(
                                items = uiState.recentActivity,
                                onDispenseClick = params.onRecentDispenseClick,
                                onInventoryClick = params.onRecentBatchClick,
                            )
                        }
                    }
                }
            }
        }
    }
}
