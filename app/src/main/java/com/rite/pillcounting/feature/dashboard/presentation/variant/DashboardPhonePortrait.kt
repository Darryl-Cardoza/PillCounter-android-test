package com.rite.pillcounting.feature.dashboard.presentation.variant

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
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
import com.rite.pillcounting.core.tourguide.tourTarget
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.responsiveSp
import com.rite.pillcounting.feature.dashboard.domain.model.DashboardTab
import com.rite.pillcounting.feature.dashboard.presentation.compose.ScaffoldKpiScrollRow
import com.rite.pillcounting.feature.dashboard.presentation.compose.ScaffoldQueueList
import com.rite.pillcounting.feature.dashboard.presentation.compose.ScaffoldQuickActionCard
import com.rite.pillcounting.feature.dashboard.presentation.compose.ScaffoldTabStrip
import com.rite.pillcounting.feature.dashboard.presentation.compose.ScaffoldTopBar
import com.rite.pillcounting.feature.dashboard.presentation.compose.buildTerminalUserLine
import com.rite.pillcounting.feature.dashboard.presentation.model.DashboardVariantParams
import com.rite.pillcounting.core.tourguide.KpiTourOrder
import com.rite.pillcounting.ui.theme.AppTheme.extendedColors
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
    val tourState = params.tourState
    val kpiScrollState = rememberScrollState()

    // The KPI row only shows ~3 of 6 cards at once. Rather than nudging the row over one
    // card per step, keep it put for the first 3 (High Priority/Pending/Controlled) and do
    // one full scroll to the end right as the tour moves past them onto Hazardous.
    LaunchedEffect(tourState.currentIndex) {
        val kpiIndex = KpiTourOrder.indexOf(tourState.currentStep?.id)
        if (kpiIndex >= 0) {
            val target = if (kpiIndex < 3) 0 else kpiScrollState.maxValue
            kpiScrollState.animateScrollTo(
                value = target,
                animationSpec = tween(durationMillis = 550, easing = FastOutSlowInEasing),
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(extendedColors.primaryBackground)
    ) {
        // Background stays full-bleed; only the content is inset so the notch
        // strip is painted instead of showing the bare window background.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .displayCutoutPadding()
        ) {
            ScaffoldTopBar(
                pharmacyName = uiState.userDetail?.profile?.pharmacyName,
                terminalAndUserLine = buildTerminalUserLine(uiState, includeTerminal = params.isHl7Enabled),
                showPmsDot = params.isHl7Enabled,
                isPmsConnected = params.isPmsConnected,
                navController = params.navController,
                compact = true,
                tourState = tourState,
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.quick_actions),
                    fontSize = responsiveSp(8.sp, boostOnPhone = true),
                    color = extendedColors.textColor,
                    fontWeight = FontWeight.SemiBold,
                )

                ScaffoldQuickActionCard(
                    title = stringResource(R.string.dispense),
                    subtitle = stringResource(R.string.tap_to_scan_rx_labels),
                    innerIconRes = R.drawable.regular_count_inner,
                    onClick = params.onDispenseQuickAction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        .tourTarget(tourState, "dispense"),
                    compact = true,
                )
                ScaffoldQuickActionCard(
                    title = stringResource(R.string.stock_count),
                    subtitle = stringResource(R.string.start_inventory_count),
                    innerIconRes = R.drawable.fixed_count_inner,
                    onClick = params.onInventoryQuickAction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        .tourTarget(tourState, "stock_count"),
                    compact = true,
                )

                ScaffoldKpiScrollRow(
                    counts = uiState.kpiCounts,
                    activeFilter = uiState.activeKpiFilter,
                    onTap = params.onKpiFilterTapped,
                    cardWidth = 132.dp,
                    cardHeight = 96.dp,
                    disabledFilters = params.disabledKpiFilters,
                    onDisabledTap = params.onDisabledKpiFilterTapped,
                    tourState = tourState,
                    scrollState = kpiScrollState,
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
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

                // Switch to the Recent Activity tab for its own explanatory step, so the
                // tooltip points at real content instead of an empty Today's Queue — then
                // back to Today's Queue once the tour moves past it, either forward into
                // the gated "dispense" step or backward to "tabs". Drives the pager
                // directly (now that it's in scope) instead of only nudging it indirectly
                // through uiState.activeTab, which raced against the pager's own
                // currentPage → onTabSelected feedback effect above and could leave the
                // pager settled on the wrong page.
                LaunchedEffect(tourState.currentStep?.id) {
                    when (tourState.currentStep?.id) {
                        "recent_activity" -> {
                            params.onTabSelected(DashboardTab.RECENT_ACTIVITY)
                            pagerState.animateScrollToPage(DashboardTab.RECENT_ACTIVITY.ordinal)
                        }
                        "tabs", "dispense" -> {
                            params.onTabSelected(DashboardTab.TODAYS_QUEUE)
                            pagerState.animateScrollToPage(DashboardTab.TODAYS_QUEUE.ordinal)
                        }
                    }
                }

                ScaffoldTabStrip(
                    activeTab = DashboardTab.entries[pagerState.currentPage],
                    onSelect = { tab ->
                        scope.launch { pagerState.animateScrollToPage(tab.ordinal) }
                    },
                    // "recent_activity" is also tagged here rather than on the (possibly
                    // empty, tiny) list content below — spotlighting the tab strip itself
                    // makes it obvious which tab is now active, instead of highlighting a
                    // small empty-state box while the tab strip dims into the background.
                    modifier = Modifier
                        .tourTarget(tourState, "tabs")
                        .tourTarget(tourState, "recent_activity"),
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
                            modifier = Modifier.tourTarget(tourState, "empty_state"),
                        )
                        DashboardTab.RECENT_ACTIVITY -> ScaffoldQueueList(
                            items = uiState.recentActivity,
                            onDispenseClick = params.onRecentDispenseClick,
                            onInventoryClick = params.onRecentBatchClick,
                            modifier = Modifier,
                        )
                    }
                }
            }
        }
    }
}
