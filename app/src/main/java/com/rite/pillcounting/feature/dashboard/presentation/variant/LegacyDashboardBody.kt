package com.rite.pillcounting.feature.dashboard.presentation.variant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.MenuButton
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.PmsConnectionIcon
import com.rite.pillcounting.core.utils.compose.SplitResponsive
import com.rite.pillcounting.feature.dashboard.presentation.compose.FixedCountSection
import com.rite.pillcounting.feature.dashboard.presentation.compose.RegularCountSection
import com.rite.pillcounting.feature.dashboard.presentation.viewmodel.DashboardViewModel
import com.rite.pillcounting.ui.theme.AppTheme

/**
 * Fallback body that renders the original `SplitResponsive` dashboard.
 *
 * Used by every variant that hasn't been redesigned yet so the app stays fully usable on
 * phones (and tablet landscape) while the new dashboard rolls out variant-by-variant.
 *
 * **Do not** add new code to this file — it's deliberately a thin wrapper around the existing
 * sections so we can delete it once all 4 variants are migrated.
 *
 * Note: pulls [DashboardViewModel] via `hiltViewModel()` because `RegularCountSection` still
 * needs the VM directly for its bucket-select dialog. That coupling will go away when Regular
 * is migrated to the new Quick Action card (Turn 3).
 */
@Composable
internal fun LegacyDashboardBody(params: DashboardVariantParams) {
    val viewModel: DashboardViewModel = hiltViewModel()
    val uiState = params.uiState

    Box(
        modifier = Modifier
            .systemBarsPadding()
            .background(AppTheme.extendedColors.secondaryBackground)
    ) {
        if (params.isHl7Enabled) {
            PmsConnectionIcon(
                modifier = Modifier.align(Alignment.TopStart),
                isPmsConnected = params.isPmsConnected,
            )
        }

        SplitResponsive(
            topOrLeft = {
                FixedCountSection(
                    completedFixedCount = uiState.completedFixedCount,
                    partialFixedCount = uiState.partialFixedCount,
                    navController = params.navController,
                    onNavigate = { viewModel.saveTxnId() },
                )
            },
            bottomOrRight = {
                RegularCountSection(
                    completedRegularCount = uiState.completedRegularCount,
                    partialRegularCount = uiState.partialRegularCount,
                    navController = params.navController,
                    onNavigate = { viewModel.saveTxnId() },
                    viewModel = viewModel,
                )
            },
        )

        MenuButton(
            params.navController,
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}
