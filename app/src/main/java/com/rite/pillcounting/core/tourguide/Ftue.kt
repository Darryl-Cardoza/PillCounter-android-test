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
    TourStep("dispense", R.string.tour_title_dispense, R.string.tour_desc_dispense),
    TourStep("stock_count", R.string.tour_title_stock_count, R.string.tour_desc_stock_count),
    TourStep("kpi_high_priority", R.string.tour_title_kpi_high_priority, R.string.tour_desc_kpi_high_priority),
    TourStep("kpi_pending", R.string.tour_title_kpi_pending, R.string.tour_desc_kpi_pending),
    TourStep("kpi_controlled", R.string.tour_title_kpi_controlled, R.string.tour_desc_kpi_controlled),
    TourStep("kpi_hazardous", R.string.tour_title_kpi_hazardous, R.string.tour_desc_kpi_hazardous),
    TourStep("kpi_cycle_count", R.string.tour_title_kpi_cycle_count, R.string.tour_desc_kpi_cycle_count),
    TourStep("kpi_pending_batch", R.string.tour_title_kpi_pending_batch, R.string.tour_desc_kpi_pending_batch),
    TourStep("tabs", R.string.tour_title_tabs, R.string.tour_desc_tabs),
    // Info-only: the dashboard variants switch to this tab (see the LaunchedEffect keyed
    // on tourState.currentStep?.id in each) so the tooltip actually points at real Recent
    // Activity content, not an empty Today's Queue.
    TourStep("recent_activity", R.string.tour_title_recent_activity, R.string.tour_desc_recent_activity),
    // The one gated dashboard action: tap Dispense to move into the dispense flow.
    TourStep("dispense", R.string.tour_title_dispense_tap, R.string.tour_desc_dispense_tap, mode = TourStepMode.ACTION_TAP),
    // Hands-free: advances itself once the Rx barcode is read (DispenseFlowScreen). The
    // camera preview fills nearly the whole screen, so pin the tooltip to the bottom edge
    // instead of letting the automatic placement stack it against the top, over the header.
    TourStep("camera_preview", R.string.tour_title_scan_rx_camera, R.string.tour_desc_scan_rx_camera, mode = TourStepMode.ACTION_WAIT, pinnedEdge = TooltipEdge.BOTTOM),
    TourStep("verify_rx_proceed", R.string.tour_title_verify_rx_proceed, R.string.tour_desc_verify_rx_proceed, mode = TourStepMode.ACTION_TAP),
    // Hands-free: advances itself once the container/NDC is scanned or counting starts.
    TourStep("camera_preview", R.string.tour_title_scan_container, R.string.tour_desc_scan_container, mode = TourStepMode.ACTION_WAIT, pinnedEdge = TooltipEdge.BOTTOM),
    // Hands-free: the same target rect toggles between an "Add" tap (mid-count)
    // and the real "All Done"/FinalDone tap (target met) — a generic tap-in-target
    // advance can't tell those apart and would fire on the very first Add, leaving
    // the tour stuck on "vial_capture" (not yet mounted — counting isn't done)
    // for the whole rest of counting. InformationPanelSection.kt's onDone lambda
    // calls advanceIfCurrent specifically, only on the real completion tap.
    TourStep("count_add_circle", R.string.tour_title_count_add_circle, R.string.tour_desc_count_add_circle, mode = TourStepMode.ACTION_WAIT),
    TourStep("vial_capture", R.string.tour_title_vial_capture, R.string.tour_desc_vial_capture, mode = TourStepMode.ACTION_TAP),
    TourStep("vial_done", R.string.tour_title_vial_done, R.string.tour_desc_vial_done, mode = TourStepMode.ACTION_TAP),
    TourStep("add_note_actions", R.string.tour_title_add_note_actions, R.string.tour_desc_add_note_actions, mode = TourStepMode.ACTION_TAP),

    // ── Stock Count flow ─────────────────────────────────────────────────────
    // Picks up right where the dispense flow left off. Re-tap of the same
    // "stock_count" target the dashboard's INFO pass already pointed at earlier.
    TourStep("stock_count", R.string.tour_title_stock_count_tap, R.string.tour_desc_stock_count_tap, mode = TourStepMode.ACTION_TAP),
    // Hands-free: the shared AlertDialog isn't gated/tagged; DashboardScreen.kt advances this
    // once a bucket is chosen. Pinned to TOP: the dialog's own window always renders above our
    // tooltip, and bottom-pinned it looked cramped against the dialog's Cancel/OK row.
    TourStep("select_bucket", R.string.tour_title_select_bucket, R.string.tour_desc_select_bucket, mode = TourStepMode.ACTION_WAIT, pinnedEdge = TooltipEdge.TOP),
    // Hands-free: advances itself once a scanned bottle becomes the active NDC
    // (InventoryScanHost.kt). Same "blocks nothing" treatment continues here.
    TourStep("inv_camera_preview", R.string.tour_title_inv_camera_preview, R.string.tour_desc_inv_camera_preview, mode = TourStepMode.ACTION_WAIT, pinnedEdge = TooltipEdge.BOTTOM),
    TourStep("inv_counter", R.string.tour_title_inv_counter, R.string.tour_desc_inv_counter, mode = TourStepMode.ACTION_TAP),
    // Optional/skippable: editing sealed/open-bottle details isn't required either.
    // Blocks nothing so SCAN PILLS/END COUNT stay tappable too — InventoryScanHost.kt
    // advances past both this and "inv_edit_actions" the moment EDIT is actually
    // tapped (landing on "inv_edit_actions" for the real Cancel/Save row), or skips
    // both in one go if the user goes straight to SCAN PILLS/END COUNT instead.
    TourStep("inv_edit_button", R.string.tour_title_inv_edit_button, R.string.tour_desc_inv_edit_button, mode = TourStepMode.ACTION_WAIT),
    TourStep("inv_edit_actions", R.string.tour_title_inv_edit_actions, R.string.tour_desc_inv_edit_actions, mode = TourStepMode.ACTION_TAP),
    // Optional/skippable: scanning loose pills isn't required to finish a stock
    // count. Blocks nothing so END COUNT stays tappable too — whichever the user
    // does first (InventoryScanHost.kt's onScanPills/onEndCount) advances past this
    // step to "inv_end_count".
    TourStep("inv_scan_pills", R.string.tour_title_inv_scan_pills, R.string.tour_desc_inv_scan_pills, mode = TourStepMode.ACTION_WAIT),
    TourStep("inv_end_count", R.string.tour_title_inv_end_count, R.string.tour_desc_inv_end_count, mode = TourStepMode.ACTION_TAP),
    // Reuses the exact same AddNoteDialog step as the dispense flow — it already
    // reads FtueController.state directly, so nothing further to wire here.
    TourStep("add_note_actions", R.string.tour_title_add_note_actions, R.string.tour_desc_add_note_actions, mode = TourStepMode.ACTION_TAP),
    // Hands-free, same treatment as "select_bucket": the real Yes/No "end this
    // count?" confirmation is a shared, generic CommonDialog — deliberately not
    // gated/tagged, so this step blocks nothing and just explains it.
    // InventoryScanHost.kt advances it (and ends the tour, being the final step)
    // the instant Yes is actually tapped.
    TourStep("end_count_confirm", R.string.tour_title_end_count_confirm, R.string.tour_desc_end_count_confirm, mode = TourStepMode.ACTION_WAIT, pinnedEdge = TooltipEdge.TOP),
)

/** KPI card order as laid out in the KPI strip — used to scroll the right card into view per step. */
val KpiTourOrder = listOf(
    "kpi_high_priority", "kpi_pending", "kpi_controlled",
    "kpi_hazardous", "kpi_cycle_count", "kpi_pending_batch",
)
