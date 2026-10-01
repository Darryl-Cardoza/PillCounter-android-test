package com.dispensesure.retail.feature.dashboard.presentation.variant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.responsiveDp
import com.dispensesure.retail.feature.dashboard.presentation.compose.DashboardBaseSizes
import com.dispensesure.retail.feature.dashboard.presentation.compose.DashboardSafeInsets
import com.dispensesure.retail.feature.dashboard.presentation.compose.DispenseAndInventoryQueueSection
import com.dispensesure.retail.feature.dashboard.presentation.compose.QuickActionsAndKpiSection
import com.dispensesure.retail.feature.dashboard.presentation.compose.ScaffoldTopBar
import com.dispensesure.retail.feature.dashboard.presentation.compose.buildTerminalUserLine
import com.dispensesure.retail.feature.dashboard.presentation.model.DashboardVariantParams
import com.dispensesure.retail.ui.theme.AppTheme.extendedColors

// Share of the space below the top bar given to quick actions + KPIs.
private const val TabletLandscapeActionsWidthFraction = 0.35f
private const val PhoneLandscapeActionsWidthFraction = 0.45f
private const val TabletPortraitActionsHeightFraction = 0.35f
private const val PhonePortraitActionsHeightFraction = 0.40f

// Actions on the left, queue on the right. Used by tablets and phones.
@Composable
fun DashboardLandscapeLayout(params: DashboardVariantParams, compact: Boolean) {
    val actionsWidthFraction = if (compact) PhoneLandscapeActionsWidthFraction else TabletLandscapeActionsWidthFraction
    DashboardScreenFrame(params = params, compact = compact) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(responsiveDp(DashboardBaseSizes.SectionGap)),
        ) {
            QuickActionsAndKpiSection(
                params = params,
                isLandscape = true,
                compact = compact,
                modifier = Modifier.weight(actionsWidthFraction).fillMaxHeight(),
            )
            DispenseAndInventoryQueueSection(
                params = params,
                modifier = Modifier.weight(1f - actionsWidthFraction).fillMaxHeight(),
            )
        }
    }
}

// Actions on top, queue below. Used by tablets and phones.
@Composable
fun DashboardPortraitLayout(params: DashboardVariantParams, compact: Boolean) {
    val actionsHeightFraction = if (compact) PhonePortraitActionsHeightFraction else TabletPortraitActionsHeightFraction
    DashboardScreenFrame(params = params, compact = compact) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(responsiveDp(DashboardBaseSizes.SectionGap)),
        ) {
            QuickActionsAndKpiSection(
                params = params,
                isLandscape = false,
                compact = compact,
                modifier = Modifier.fillMaxWidth().weight(actionsHeightFraction),
            )
            DispenseAndInventoryQueueSection(
                params = params,
                modifier = Modifier.fillMaxWidth().weight(1f - actionsHeightFraction),
            )
        }
    }
}

// Background, system-bar insets and top bar shared by both layouts.
@Composable
private fun DashboardScreenFrame(
    params: DashboardVariantParams,
    compact: Boolean,
    content: @Composable () -> Unit,
) {
    val uiState = params.uiState
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(extendedColors.primaryBackground)
    ) {
        // Background stays full-bleed; only the content is inset so the notch
        // strip is painted instead of showing the bare window background.
        // The top bar insets itself (top + sides); the body takes bottom + sides.
        Column(modifier = Modifier.fillMaxSize()) {
            ScaffoldTopBar(
                pharmacyName = uiState.userDetail?.profile?.pharmacyName,
                terminalAndUserLine = buildTerminalUserLine(uiState, includeTerminal = params.isHl7Enabled),
                showPmsDot = params.isHl7Enabled,
                isPmsConnected = params.isPmsConnected,
                navController = params.navController,
                compact = compact,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .windowInsetsPadding(
                        DashboardSafeInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                    )
                    .padding(responsiveDp(DashboardBaseSizes.ScreenEdgePadding)),
            ) {
                content()
            }
        }
    }
}
