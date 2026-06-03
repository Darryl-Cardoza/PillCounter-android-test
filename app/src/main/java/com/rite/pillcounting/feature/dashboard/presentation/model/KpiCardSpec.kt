package com.rite.pillcounting.feature.dashboard.presentation.model

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.PriorityHigh
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Task
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.ui.graphics.vector.ImageVector
import com.rite.pillcounting.R
import com.rite.pillcounting.feature.dashboard.domain.model.KpiFilter

/**
 * Presentation-layer spec for a single dashboard KPI card: which [KpiFilter] it
 * applies, the two label lines (string resources), and the icon. This is a UI
 * concern (carries [androidx.annotation.StringRes] ids and a Compose [ImageVector]),
 * so it lives in presentation/model rather than domain/model.
 */
internal data class KpiCardSpec(
    val filter: KpiFilter,
    @StringRes val lineOneRes: Int,
    @StringRes val lineTwoRes: Int,
    val icon: ImageVector,
)

internal val DefaultKpiCards: List<KpiCardSpec> = listOf(
    KpiCardSpec(KpiFilter.DISP_HIGH_PRIORITY, R.string.kpi_disp_short, R.string.kpi_high_priority, Icons.Outlined.PriorityHigh),
    KpiCardSpec(KpiFilter.DISP_PENDING, R.string.kpi_disp_short, R.string.kpi_pending, Icons.Outlined.Refresh),
    KpiCardSpec(KpiFilter.DISP_CONTROLLED, R.string.kpi_disp_short, R.string.kpi_cont_drugs, Icons.Outlined.Shield),
    KpiCardSpec(KpiFilter.DISP_HAZARDOUS, R.string.kpi_disp_short, R.string.kpi_hazardous, Icons.Outlined.Warning),
    KpiCardSpec(KpiFilter.INV_CYCLE_COUNT, R.string.kpi_inv_short, R.string.kpi_cycle_count, Icons.Outlined.Task),
    KpiCardSpec(KpiFilter.INV_PENDING_BATCH, R.string.kpi_inv_short, R.string.kpi_pending_batch, Icons.Outlined.Inventory2),
)