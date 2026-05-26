package com.rite.pillcounting.feature.dashboard.presentation.variant

import androidx.compose.runtime.Composable

/**
 * Tablet landscape dashboard variant.
 *
 * **Status: stub (Turn 1).** Renders the legacy `SplitResponsive` layout. Will be replaced
 * once the tablet-landscape redesign is approved.
 */
@Composable
fun DashboardTabletLandscape(params: DashboardVariantParams) {
    LegacyDashboardBody(params)
}
