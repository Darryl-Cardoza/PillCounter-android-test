package com.rite.pillcounting.feature.dashboard.presentation.variant

import androidx.compose.runtime.Composable

/**
 * Phone landscape dashboard variant.
 *
 * **Status: stub (Turn 1).** Renders the legacy `SplitResponsive` layout. Will be replaced
 * once the phone-landscape redesign is approved.
 */
@Composable
fun DashboardPhoneLandscape(params: DashboardVariantParams) {
    LegacyDashboardBody(params)
}
