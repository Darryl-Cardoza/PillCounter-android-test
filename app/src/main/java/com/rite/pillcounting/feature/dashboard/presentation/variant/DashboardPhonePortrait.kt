package com.rite.pillcounting.feature.dashboard.presentation.variant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rite.pillcounting.R
import com.rite.pillcounting.feature.dashboard.domain.model.DashboardTab
import com.rite.pillcounting.feature.dashboard.presentation.compose.DashboardPageBackground
import com.rite.pillcounting.feature.dashboard.presentation.compose.DashboardSubtleText
import com.rite.pillcounting.feature.dashboard.presentation.compose.ScaffoldKpiScrollRow
import com.rite.pillcounting.feature.dashboard.presentation.compose.ScaffoldQueueList
import com.rite.pillcounting.feature.dashboard.presentation.compose.ScaffoldQuickActionCard
import com.rite.pillcounting.feature.dashboard.presentation.compose.ScaffoldTabStrip
import com.rite.pillcounting.feature.dashboard.presentation.compose.ScaffoldTopBar
import com.rite.pillcounting.feature.dashboard.presentation.compose.buildTerminalUserLine
import kotlinx.coroutines.launch

/**
 * Phone portrait dashboard variant — matches the provided Figma reference.
 *
 * Same data + callbacks as the tablet variants; placement adapted for narrow widths:
 *   - Top bar (pharmacy + terminal/user on left, PMS dot + menu on right).
 *   - QUICK ACTIONS label.
 *   - Two Quick Action cards stacked vertically (full width each).
 *   - 6 KPI cards in a horizontally scrollable single row (fixed-width cards).
 *   - Tab strip, then paged Today's Queue / Recent Activity list filling remaining space.
 */
@Composable
fun DashboardPhonePortrait(params: DashboardVariantParams) {
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
                compact = true,
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "QUICK ACTIONS",
                    style = MaterialTheme.typography.labelSmall,
                    color = DashboardSubtleText,
                    fontWeight = FontWeight.SemiBold,
                )

                ScaffoldQuickActionCard(
                    title = "Dispense",
                    subtitle = "Tap to scan Rx Labels",
                    innerIconRes = R.drawable.regular_count_inner,
                    onClick = params.onDispenseQuickAction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp),
                    compact = true,
                )
                ScaffoldQuickActionCard(
                    title = "Stock Count",
                    subtitle = "Start inventory count",
                    innerIconRes = R.drawable.fixed_count_inner,
                    onClick = params.onInventoryQuickAction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp),
                    compact = true,
                )

                ScaffoldKpiScrollRow(
                    counts = uiState.kpiCounts,
                    activeFilter = uiState.activeKpiFilter,
                    onTap = params.onKpiFilterTapped,
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = Color(0x14000000),
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
                    pageSpacing = 12.dp,
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
