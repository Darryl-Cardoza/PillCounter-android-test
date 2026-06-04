package com.rite.pillcounting.feature.dashboard.domain.model

/**
 * Filter applied to Today's Queue when a KPI shortcut card is tapped.
 *
 * Each card on the dashboard's KPI row is backed by one of these values.
 * Tapping a card toggles the filter; tapping the same card again clears it
 * (handled in the ViewModel — see `setKpiFilter`).
 *
 * Counts shown on each card are derived from the same combined queue list
 * that powers Today's Queue, so the card count and the filtered list size
 * always agree by construction.
 */
enum class KpiFilter {
    /** All dispense rows flagged as high-priority. Stubbed until the priority field is confirmed. */
    DISP_HIGH_PRIORITY,

    /** All in-progress dispense (FIXED) transactions. */
    DISP_PENDING,

    /** Dispense rows whose drug type matches the controlled-substance list. Stubbed until list provided. */
    DISP_CONTROLLED,

    /** Dispense rows whose joined drug has isHazardous = true. */
    DISP_HAZARDOUS,

    /** Inventory cycle-count batches. Deferred to iteration 2 — currently stubbed. */
    INV_CYCLE_COUNT,

    /** Inventory batches in INPROGRESS status. */
    INV_PENDING_BATCH,
}
