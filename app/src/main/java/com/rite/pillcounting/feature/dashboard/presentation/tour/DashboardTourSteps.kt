package com.rite.pillcounting.feature.dashboard.presentation.tour

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.rite.pillcounting.R
import com.rite.pillcounting.core.tourguide.TourStep

/** Screen id the dashboard's tour guide is persisted under, shared by every variant. */
internal const val DASHBOARD_TOUR_SCREEN_ID = "dashboard_home"

/**
 * The dashboard's tour steps, shared by every variant (phone/tablet, portrait/landscape) —
 * each variant tags the same [TourStep.id]s onto whichever composable it places that element
 * in, so the copy and step order stay identical no matter the layout.
 */
@Composable
internal fun dashboardTourSteps(): List<TourStep> = listOf(
    TourStep("pms", stringResource(R.string.tour_title_pms), stringResource(R.string.tour_desc_pms)),
    TourStep("dispense", stringResource(R.string.tour_title_dispense), stringResource(R.string.tour_desc_dispense)),
    TourStep("stock_count", stringResource(R.string.tour_title_stock_count), stringResource(R.string.tour_desc_stock_count)),
    TourStep("kpi_high_priority", stringResource(R.string.tour_title_kpi_high_priority), stringResource(R.string.tour_desc_kpi_high_priority)),
    TourStep("kpi_pending", stringResource(R.string.tour_title_kpi_pending), stringResource(R.string.tour_desc_kpi_pending)),
    TourStep("kpi_controlled", stringResource(R.string.tour_title_kpi_controlled), stringResource(R.string.tour_desc_kpi_controlled)),
    TourStep("kpi_hazardous", stringResource(R.string.tour_title_kpi_hazardous), stringResource(R.string.tour_desc_kpi_hazardous)),
    TourStep("kpi_cycle_count", stringResource(R.string.tour_title_kpi_cycle_count), stringResource(R.string.tour_desc_kpi_cycle_count)),
    TourStep("kpi_pending_batch", stringResource(R.string.tour_title_kpi_pending_batch), stringResource(R.string.tour_desc_kpi_pending_batch)),
    TourStep("tabs", stringResource(R.string.tour_title_tabs), stringResource(R.string.tour_desc_tabs)),
)

/** KPI card order as laid out in the KPI strip — used to scroll the right card into view per step. */
internal val KpiTourOrder = listOf(
    "kpi_high_priority", "kpi_pending", "kpi_controlled",
    "kpi_hazardous", "kpi_cycle_count", "kpi_pending_batch",
)
