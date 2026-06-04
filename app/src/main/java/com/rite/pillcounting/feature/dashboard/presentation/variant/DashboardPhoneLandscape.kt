package com.rite.pillcounting.feature.dashboard.presentation.variant

import com.rite.pillcounting.feature.dashboard.presentation.model.DashboardVariantParams

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rite.pillcounting.R
import com.rite.pillcounting.feature.dashboard.domain.model.DashboardTab
import com.rite.pillcounting.feature.dashboard.presentation.compose.ScaffoldKpiScrollColumn
import com.rite.pillcounting.feature.dashboard.presentation.compose.ScaffoldQueueList
import com.rite.pillcounting.feature.dashboard.presentation.compose.ScaffoldQuickActionCard
import com.rite.pillcounting.feature.dashboard.presentation.compose.ScaffoldTabStrip
import com.rite.pillcounting.feature.dashboard.presentation.compose.ScaffoldTopBar
import com.rite.pillcounting.feature.dashboard.presentation.compose.buildTerminalUserLine
import com.rite.pillcounting.ui.theme.AppTheme.extendedColors
import kotlinx.coroutines.launch

/**
 * Phone landscape dashboard variant — matches the provided Figma reference.
 *
 * Same data + callbacks as the other variants; placement adapted for short, wide screens:
 *   - Top bar (compact) spans full width.
 *   - Sub-header row: "QUICK ACTIONS" label over the left columns, tab strip over the right column.
 *   - Body splits into 3 side-by-side columns:
 *       1. Quick Action cards stacked vertically (Dispense top, Stock Count bottom).
 *       2. 6 KPI cards in a vertically scrolling single column.
 *       3. Today's Queue / Recent Activity pager.
 */
@Composable
fun DashboardPhoneLandscape(params: DashboardVariantParams) {
    val uiState = params.uiState

    Box(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .background(extendedColors.primaryBackground)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScaffoldTopBar(
                pharmacyName = uiState.userDetail?.profile?.pharmacyName,
                terminalAndUserLine = buildTerminalUserLine(uiState),
                showPmsDot = params.isHl7Enabled,
                isPmsConnected = params.isPmsConnected,
                navController = params.navController,
                compact = true,
            )

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

            LaunchedEffect(uiState.activeTab) {
                if (pagerState.currentPage != uiState.activeTab.ordinal) {
                    pagerState.animateScrollToPage(uiState.activeTab.ordinal)
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Spacer(modifier = Modifier.height(4.dp))

                // Sub-header: QUICK ACTIONS over the left columns, tab strip over the queue column.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.quick_actions),
                        style = MaterialTheme.typography.labelSmall,
                        color = extendedColors.textColor,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(0.55f),
                    )
                    ScaffoldTabStrip(
                        activeTab = DashboardTab.entries[pagerState.currentPage],
                        onSelect = { tab ->
                            scope.launch { pagerState.animateScrollToPage(tab.ordinal) }
                        },
                        modifier = Modifier.weight(0.45f),
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Column 1 — Quick Actions stacked vertically.
                    Column(
                        modifier = Modifier
                            .weight(0.35f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ScaffoldQuickActionCard(
                            title = stringResource(R.string.dispense),
                            subtitle = stringResource(R.string.tap_to_scan_rx_labels),
                            innerIconRes = R.drawable.regular_count_inner,
                            onClick = params.onDispenseQuickAction,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            compact = true,
                        )
                        ScaffoldQuickActionCard(
                            title = stringResource(R.string.stock_count),
                            subtitle = stringResource(R.string.start_inventory_count),
                            innerIconRes = R.drawable.fixed_count_inner,
                            onClick = params.onInventoryQuickAction,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            compact = true,
                        )
                    }

                    // Column 2 — vertically scrolling KPI strip.
                    ScaffoldKpiScrollColumn(
                        counts = uiState.kpiCounts,
                        activeFilter = uiState.activeKpiFilter,
                        onTap = params.onKpiFilterTapped,
                        modifier = Modifier
                            .weight(0.20f)
                            .fillMaxHeight(),
                    )

                    // Column 3 — paged Today's Queue / Recent Activity list.
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .weight(0.45f)
                            .fillMaxHeight(),
                    ) { page ->
                        when (DashboardTab.entries[page]) {
                            DashboardTab.TODAYS_QUEUE -> ScaffoldQueueList(
                                items = uiState.queue,
                                onDispenseClick = params.onQueueDispenseClick,
                                onInventoryClick = params.onQueueInventoryClick,
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
