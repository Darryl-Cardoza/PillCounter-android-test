package com.rite.pillcounting.feature.dashboard.presentation.variant

import androidx.compose.runtime.Composable

/**
 * Phone portrait dashboard variant.
 *
 * **Status: stub (Turn 1).** Renders the legacy `SplitResponsive` layout so phones stay fully
 * usable while the new dashboard rolls out variant-by-variant. The real phone-portrait redesign
 * will replace this body in a later iteration.
 */
@Composable
fun DashboardPhonePortrait(params: DashboardVariantParams) {
    LegacyDashboardBody(params)
}
