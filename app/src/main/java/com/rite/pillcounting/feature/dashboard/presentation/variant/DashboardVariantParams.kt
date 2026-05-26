package com.rite.pillcounting.feature.dashboard.presentation.variant

import androidx.navigation.NavController
import com.rite.pillcounting.feature.dashboard.domain.model.DashboardTab
import com.rite.pillcounting.feature.dashboard.domain.model.DashboardUiState
import com.rite.pillcounting.feature.dashboard.domain.model.KpiFilter

/**
 * Parameter bag shared by every dashboard variant composable.
 *
 * The 4 variants (phone P/L + tablet P/L) **must** consume the same data — only UI placement
 * differs. Centralizing the parameter list here enforces that contract at compile time: a
 * variant that needs different inputs would have to introduce a new type, which the dispatcher
 * would refuse to construct.
 *
 * The bag carries:
 *  - [uiState] the rendered state
 *  - [isPmsConnected] live HL7 connection status (separate flow on the VM)
 *  - [isHl7Enabled] static config flag
 *  - [navController] for navigation actions handled inside the variant
 *  - callbacks for dashboard-local interactions ([onKpiFilterTapped], [onTabSelected])
 *  - [onDispenseQuickAction] / [onInventoryQuickAction] for the two Quick Action cards. The
 *    dispatcher wires these to the existing Fixed / Regular entry flows (including the
 *    bucket-select + new/resume dialogs for inventory).
 */
data class DashboardVariantParams(
    val uiState: DashboardUiState,
    val isPmsConnected: Boolean,
    val isHl7Enabled: Boolean,
    val navController: NavController,
    val onKpiFilterTapped: (KpiFilter) -> Unit,
    val onTabSelected: (DashboardTab) -> Unit,
    val onDispenseQuickAction: () -> Unit,
    val onInventoryQuickAction: () -> Unit,
    /** Tap on a completed dispense row in Recent Activity — routes to HistoryDetail. */
    val onRecentDispenseClick: (txnId: Long) -> Unit,
    /** Tap on a completed batch row in Recent Activity — routes to BatchHistoryDetail. */
    val onRecentBatchClick: (batchId: Long) -> Unit,
)
