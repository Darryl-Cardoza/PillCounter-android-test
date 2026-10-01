package com.dispensesure.retail.feature.dashboard.presentation.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dispensesure.retail.R
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.responsiveDp
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.responsiveSp
import com.dispensesure.retail.feature.dashboard.domain.model.DashboardTab
import com.dispensesure.retail.feature.dashboard.presentation.model.DashboardVariantParams
import com.dispensesure.retail.feature.dashboard.presentation.model.toKpiSectionState
import com.dispensesure.retail.ui.theme.AppTheme.extendedColors

// Landscape: quick actions take the top 32 % of the section, the KPI list the rest.
private const val LandscapeQuickActionsHeightShare = 0.32f

// "QUICK ACTIONS" label, the Dispense + Inventory cards, then the KPIs.
@Composable
internal fun QuickActionsAndKpiSection(
    params: DashboardVariantParams,
    isLandscape: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val kpiState = params.toKpiSectionState()
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(responsiveDp(DashboardBaseSizes.SectionGap)),
    ) {
        Text(
            text = stringResource(R.string.quick_actions),
            fontSize = responsiveSp(DashboardBaseSizes.SectionLabelText),
            color = extendedColors.textColor,
            fontWeight = FontWeight.SemiBold,
        )
        if (isLandscape) {
            QuickActionCardsRow(
                params = params,
                ringAboveText = !compact,
                modifier = Modifier.fillMaxWidth().weight(LandscapeQuickActionsHeightShare),
            )
            LandscapeKpiList(
                state = kpiState,
                compact = compact,
                modifier = Modifier.fillMaxWidth().weight(1f - LandscapeQuickActionsHeightShare),
            )
        } else {
            QuickActionCardsRow(
                params = params,
                ringAboveText = false,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            PortraitKpiRow(
                state = kpiState,
                compact = compact,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

@Composable
private fun QuickActionCardsRow(
    params: DashboardVariantParams,
    ringAboveText: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(responsiveDp(DashboardBaseSizes.CardGap)),
    ) {
        ScaffoldQuickActionCard(
            title = stringResource(R.string.dispense),
            subtitle = stringResource(R.string.tap_to_scan_rx_labels),
            innerIconRes = R.drawable.regular_count_inner,
            onClick = params.onDispenseQuickAction,
            centered = ringAboveText,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        ScaffoldQuickActionCard(
            title = stringResource(R.string.stock_count),
            subtitle = stringResource(R.string.start_inventory_count),
            innerIconRes = R.drawable.fixed_count_inner,
            onClick = params.onInventoryQuickAction,
            centered = ringAboveText,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

// Tab strip over the pager. Pager page index = DashboardTab ordinal; the two
// effects keep the pager and the ViewModel's activeTab in sync both ways.
@Composable
internal fun DispenseAndInventoryQueueSection(
    params: DashboardVariantParams,
    modifier: Modifier = Modifier,
) {
    val uiState = params.uiState
    val pagerState = rememberPagerState(
        initialPage = uiState.activeTab.ordinal,
        pageCount = { DashboardTab.entries.size },
    )
    val onTabSelected by rememberUpdatedState(params.onTabSelected)

    // Report a page only once scrolling stops, so an animation passing a middle tab is ignored.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            onTabSelected(DashboardTab.entries[page])
        }
    }

    LaunchedEffect(uiState.activeTab) {
        if (pagerState.currentPage != uiState.activeTab.ordinal) {
            pagerState.animateScrollToPage(uiState.activeTab.ordinal)
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(responsiveDp(2.dp)),
    ) {
        ScaffoldTabStrip(
            activeTab = DashboardTab.entries[pagerState.currentPage],
            dispenseCount = uiState.dispenseTabCount,
            inventoryCount = uiState.inventoryTabCount,
            onSelect = params.onTabSelected,
        )
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
            pageSpacing = responsiveDp(DashboardBaseSizes.CardGap),
        ) { page ->
            val tab = DashboardTab.entries[page]
            // The pager draws a page as a swipe brings it on screen; start its load then.
            LaunchedEffect(tab) { params.onTabVisible(tab) }
            val isLoaded = tab in uiState.loadedTabs
            when (tab) {
                DashboardTab.DISPENSE_QUEUE -> ScaffoldQueueList(
                    items = uiState.dispenseQueue,
                    onDispenseClick = params.onQueueDispenseClick,
                    onInventoryClick = params.onQueueInventoryClick,
                    showUpNextCard = true,
                    isLoaded = isLoaded,
                )
                DashboardTab.INVENTORY_QUEUE -> ScaffoldQueueList(
                    items = uiState.inventoryQueue,
                    onDispenseClick = params.onQueueDispenseClick,
                    onInventoryClick = params.onQueueInventoryClick,
                    isLoaded = isLoaded,
                )
                DashboardTab.RECENT_ACTIVITY -> ScaffoldQueueList(
                    items = uiState.recentActivity,
                    onDispenseClick = params.onRecentDispenseClick,
                    onInventoryClick = params.onRecentBatchClick,
                    isLoaded = isLoaded,
                )
            }
        }
    }
}
