package com.rite.pillcounting.core.tourguide

import com.rite.pillcounting.R

/** Pref key the whole app-wide first-time-user-experience is tracked under. */
const val FTUE_ID = "ftue_v1"

/**
 * The one-time, cross-screen "game tutorial" walk: dashboard tour, then gated one-action-
 * at-a-time through the dispense flow. A single [TourGuideState] shared everywhere via this
 * singleton — not a `remember`ed instance — so it survives navigating from the dashboard to
 * the dispense screen instead of being disposed with the composable that created it.
 */
object FtueController {
    val state: TourGuideState = TourGuideState(FTUE_STEPS)
}

/**
 * Ordered end-to-end script. Informational dashboard steps keep the classic Skip/Back/Next
 * tooltip ([TourStepMode.INFO], the default); every real action from here on gates the
 * screen to that one allowed thing and advances itself — see [TourStepMode].
 */
val FTUE_STEPS: List<TourStep> = listOf(
    TourStep("pms", R.string.tour_title_pms, R.string.tour_desc_pms),
    TourStep("stock_count", R.string.tour_title_stock_count, R.string.tour_desc_stock_count),
    TourStep("kpi_high_priority", R.string.tour_title_kpi_high_priority, R.string.tour_desc_kpi_high_priority),
    TourStep("kpi_pending", R.string.tour_title_kpi_pending, R.string.tour_desc_kpi_pending),
    TourStep("kpi_controlled", R.string.tour_title_kpi_controlled, R.string.tour_desc_kpi_controlled),
    TourStep("kpi_hazardous", R.string.tour_title_kpi_hazardous, R.string.tour_desc_kpi_hazardous),
    TourStep("kpi_cycle_count", R.string.tour_title_kpi_cycle_count, R.string.tour_desc_kpi_cycle_count),
    TourStep("kpi_pending_batch", R.string.tour_title_kpi_pending_batch, R.string.tour_desc_kpi_pending_batch),
    TourStep("tabs", R.string.tour_title_tabs, R.string.tour_desc_tabs),
    // The one gated dashboard action: tap Dispense to move into the dispense flow.
    TourStep("dispense", R.string.tour_title_dispense, R.string.tour_desc_dispense, mode = TourStepMode.ACTION_TAP),
    // Hands-free: advances itself once the Rx barcode is read (DispenseFlowScreen). The
    // camera preview fills nearly the whole screen, so pin the tooltip to the bottom edge
    // instead of letting the automatic placement stack it against the top, over the header.
    TourStep("camera_preview", R.string.tour_title_scan_rx_camera, R.string.tour_desc_scan_rx_camera, mode = TourStepMode.ACTION_WAIT, pinnedEdge = TooltipEdge.BOTTOM),
    TourStep("verify_rx_proceed", R.string.tour_title_verify_rx_proceed, R.string.tour_desc_verify_rx_proceed, mode = TourStepMode.ACTION_TAP),
    // Hands-free: advances itself once the container/NDC is scanned or counting starts.
    TourStep("camera_preview", R.string.tour_title_scan_container, R.string.tour_desc_scan_container, mode = TourStepMode.ACTION_WAIT, pinnedEdge = TooltipEdge.BOTTOM),
    TourStep("count_add_circle", R.string.tour_title_count_add_circle, R.string.tour_desc_count_add_circle, mode = TourStepMode.ACTION_TAP),
    TourStep("vial_capture", R.string.tour_title_vial_capture, R.string.tour_desc_vial_capture, mode = TourStepMode.ACTION_TAP),
    TourStep("vial_done", R.string.tour_title_vial_done, R.string.tour_desc_vial_done, mode = TourStepMode.ACTION_TAP),
    TourStep("add_note_actions", R.string.tour_title_add_note_actions, R.string.tour_desc_add_note_actions, mode = TourStepMode.ACTION_TAP),
)

/** KPI card order as laid out in the KPI strip — used to scroll the right card into view per step. */
val KpiTourOrder = listOf(
    "kpi_high_priority", "kpi_pending", "kpi_controlled",
    "kpi_hazardous", "kpi_cycle_count", "kpi_pending_batch",
)
