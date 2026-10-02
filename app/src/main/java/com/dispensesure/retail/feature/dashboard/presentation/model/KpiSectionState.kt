package com.dispensesure.retail.feature.dashboard.presentation.model

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.dispensesure.retail.feature.dashboard.domain.model.KpiFilter

// Everything the KPI cards need, pulled out of DashboardVariantParams once.
@Immutable
internal data class KpiSectionState(
    val counts: Map<KpiFilter, Int>,
    val selectedFilter: KpiFilter?,
    val disabledFilters: Set<KpiFilter>,
    val onKpiTapped: (KpiFilter) -> Unit,
    val onDisabledKpiTapped: (KpiFilter) -> Unit,
)

// One ready-to-draw KPI card. onClick is already routed to the right callback.
@Immutable
internal data class KpiCardItem(
    val filter: KpiFilter,
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    @DrawableRes val iconRes: Int,
    val count: Int,
    val isSelected: Boolean,
    val isDisabled: Boolean,
    val onClick: () -> Unit,
)

// Title is the filter name ("High Priority"), subtitle the queue ("Dispense"),
// which is lineTwo over lineOne in KpiCardSpec.
internal fun KpiSectionState.toCardItems(
    specs: List<KpiCardSpec> = DefaultKpiCards,
): List<KpiCardItem> = specs.map { spec ->
    val isDisabled = spec.filter in disabledFilters
    KpiCardItem(
        filter = spec.filter,
        titleRes = spec.lineTwoRes,
        subtitleRes = spec.lineOneRes,
        iconRes = spec.iconRes,
        count = counts[spec.filter] ?: 0,
        isSelected = spec.filter == selectedFilter,
        isDisabled = isDisabled,
        onClick = if (isDisabled) {
            { onDisabledKpiTapped(spec.filter) }
        } else {
            { onKpiTapped(spec.filter) }
        },
    )
}

internal fun DashboardVariantParams.toKpiSectionState() = KpiSectionState(
    counts = uiState.kpiCounts,
    selectedFilter = uiState.activeKpiFilter,
    disabledFilters = disabledKpiFilters,
    onKpiTapped = onKpiFilterTapped,
    onDisabledKpiTapped = onDisabledKpiFilterTapped,
)
