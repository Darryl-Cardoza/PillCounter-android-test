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
    // Info-only: the dashboard variants switch to this tab (see the LaunchedEffect keyed
    // on tourState.currentStep?.id in each) so the tooltip actually points at real Recent
    // Activity content, not an empty Today's Queue.
    TourStep("recent_activity", R.string.tour_title_recent_activity, R.string.tour_desc_recent_activity),
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

    // ── Stock Count flow ─────────────────────────────────────────────────────
    // Picks up right where the dispense flow left off. Re-tap of the same
    // "stock_count" target the dashboard's INFO pass already pointed at earlier.
    TourStep("stock_count", R.string.tour_title_stock_count_tap, R.string.tour_desc_stock_count_tap, mode = TourStepMode.ACTION_TAP),
    // Hands-free: advances itself once a scanned bottle becomes the active NDC
    // (InventoryScanHost.kt). The bucket-select dialog shown right after tapping
    // Inventory, above, is deliberately left ungated — this step blocks nothing,
    // so it's tolerated exactly like the dispense flow's hands-free scan steps
    // tolerate the RX/NDC verify sheets appearing in between.
    TourStep("inv_camera_preview", R.string.tour_title_inv_camera_preview, R.string.tour_desc_inv_camera_preview, mode = TourStepMode.ACTION_WAIT, pinnedEdge = TooltipEdge.BOTTOM),
    TourStep("inv_counter", R.string.tour_title_inv_counter, R.string.tour_desc_inv_counter, mode = TourStepMode.ACTION_TAP),
    TourStep("inv_edit_button", R.string.tour_title_inv_edit_button, R.string.tour_desc_inv_edit_button, mode = TourStepMode.ACTION_TAP),
    TourStep("inv_edit_actions", R.string.tour_title_inv_edit_actions, R.string.tour_desc_inv_edit_actions, mode = TourStepMode.ACTION_TAP),
    // Hands off into a nested DispenseFlowScreen session to count loose pills —
    // not re-gated once there; "inv_end_count"'s target simply won't be mounted
    // until the user returns, which the "pass through when unmounted" fail-safe
    // in TourGuideOverlay already handles.
    TourStep("inv_scan_pills", R.string.tour_title_inv_scan_pills, R.string.tour_desc_inv_scan_pills, mode = TourStepMode.ACTION_TAP),
    TourStep("inv_end_count", R.string.tour_title_inv_end_count, R.string.tour_desc_inv_end_count, mode = TourStepMode.ACTION_TAP),
    // Reuses the exact same AddNoteDialog step as the dispense flow — it already
    // reads FtueController.state directly, so nothing further to wire here. The
    // tour ends here; the end-count Yes/No confirmation after this is left
    // ungated (see plan notes — CommonDialog is a shared, generic AlertDialog).
    TourStep("add_note_actions", R.string.tour_title_add_note_actions, R.string.tour_desc_add_note_actions, mode = TourStepMode.ACTION_TAP),
)

/** KPI card order as laid out in the KPI strip — used to scroll the right card into view per step. */
val KpiTourOrder = listOf(
    "kpi_high_priority", "kpi_pending", "kpi_controlled",
    "kpi_hazardous", "kpi_cycle_count", "kpi_pending_batch",
)
