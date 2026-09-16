package com.rite.pillcounting.feature.dashboard.presentation.variant

import com.rite.pillcounting.feature.dashboard.presentation.model.DashboardVariantParams

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rite.pillcounting.R
import com.rite.pillcounting.feature.dashboard.domain.model.DashboardTab
import com.rite.pillcounting.feature.dashboard.presentation.compose.ScaffoldKpiRow
import com.rite.pillcounting.feature.dashboard.presentation.compose.ScaffoldQueueList
import com.rite.pillcounting.feature.dashboard.presentation.compose.ScaffoldQuickActionCard
import com.rite.pillcounting.feature.dashboard.presentation.compose.ScaffoldTabStrip
import com.rite.pillcounting.feature.dashboard.presentation.compose.ScaffoldTopBar
import com.rite.pillcounting.feature.dashboard.presentation.compose.buildTerminalUserLine
import com.rite.pillcounting.ui.theme.AppTheme.extendedColors
import kotlinx.coroutines.launch

/**
 * Tablet portrait dashboard variant — reference implementation.
 *
 * Composes the shared `Scaffold*` widgets in a vertical stack: top bar, "QUICK ACTIONS" label,
 * two Quick Action cards side-by-side, KPI row, divider, tab strip, paged queue/recent activity.
 */
@Composable
fun DashboardTabletPortrait(params: DashboardVariantParams) {
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
                terminalAndUserLine = buildTerminalUserLine(uiState, includeTerminal = params.isHl7Enabled),
                showPmsDot = params.isHl7Enabled,
                isPmsConnected = params.isPmsConnected,
                navController = params.navController,
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.quick_actions),
                    fontSize = 16.sp,
                    color = extendedColors.textColor,
                    fontWeight = FontWeight.SemiBold,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ScaffoldQuickActionCard(
                        title = stringResource(R.string.dispense),
                        subtitle = stringResource(R.string.tap_to_scan_rx_labels),
                        innerIconRes = R.drawable.regular_count_inner,
                        onClick = params.onDispenseQuickAction,
                        modifier = Modifier
                            .weight(1f)
                            .height(240.dp),
                        iconSize = 110.dp
                    )
                    ScaffoldQuickActionCard(
                        title = stringResource(R.string.inventory),
                        subtitle = stringResource(R.string.start_inventory_count),
                        innerIconRes = R.drawable.fixed_count_inner,
                        onClick = params.onInventoryQuickAction,
                        modifier = Modifier
                            .weight(1f)
                            .height(240.dp),
                        iconSize = 110.dp
                    )
                }

                ScaffoldKpiRow(
                    counts = uiState.kpiCounts,
                    activeFilter = uiState.activeKpiFilter,
                    onTap = params.onKpiFilterTapped,
                    disabledFilters = params.disabledKpiFilters,
                    onDisabledTap = params.onDisabledKpiFilterTapped,
                    cardHeight = 150.dp,
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = extendedColors.primaryBackground,
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
