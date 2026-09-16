package com.rite.pillcounting.feature.dashboard.presentation.model

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.rite.pillcounting.R
import com.rite.pillcounting.feature.dashboard.domain.model.KpiFilter

/**
 * Presentation-layer spec for a single dashboard KPI card: which [KpiFilter] it
 * applies, the two label lines (string resources), and the drawable icon res.
 */
internal data class KpiCardSpec(
    val filter: KpiFilter,
    @StringRes val lineOneRes: Int,
    @StringRes val lineTwoRes: Int,
    @DrawableRes val iconRes: Int,
)

internal val DefaultKpiCards: List<KpiCardSpec> = listOf(
    KpiCardSpec(KpiFilter.DISP_HIGH_PRIORITY, R.string.kpi_disp_short, R.string.kpi_high_priority, R.drawable.priorityhigh),
    KpiCardSpec(KpiFilter.DISP_PENDING, R.string.kpi_disp_short, R.string.kpi_pending, R.drawable.partial),
    KpiCardSpec(KpiFilter.DISP_CONTROLLED, R.string.kpi_disp_short, R.string.kpi_cont_drugs, R.drawable.prescription_icon),
    KpiCardSpec(KpiFilter.DISP_HAZARDOUS, R.string.kpi_disp_short, R.string.kpi_hazardous, R.drawable.warning),
    KpiCardSpec(KpiFilter.INV_CYCLE_COUNT, R.string.kpi_inv_short, R.string.kpi_cycle_count, R.drawable.prescription_icon),
    KpiCardSpec(KpiFilter.INV_PENDING_BATCH, R.string.kpi_inv_short, R.string.kpi_pending_batch, R.drawable.stock),
)
