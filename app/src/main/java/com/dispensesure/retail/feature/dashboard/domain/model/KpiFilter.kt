package com.dispensesure.retail.feature.dashboard.domain.model

/**
 * Filter applied to its own [queue] when a KPI shortcut card is tapped.
 *
 * Each card on the dashboard's KPI row is backed by one of these values.
 * Tapping a card toggles the filter; tapping the same card again clears it
 * (handled in the ViewModel — see `onKpiFilterTapped`).
 *
 * Dispense counts come from the unfiltered dispense list; inventory counts come from
 * `BatchDao.observeInProgressBatchKpiCounts`.
 */
enum class KpiFilter(val queue: DashboardTab) {
    /** All dispense rows flagged as high-priority. Stubbed until the priority field is confirmed. */
    DISP_HIGH_PRIORITY(DashboardTab.DISPENSE_QUEUE),

    /** All in-progress dispense (FIXED) transactions. */
    DISP_PENDING(DashboardTab.DISPENSE_QUEUE),

    /** Dispense rows whose drug type matches the controlled-substance list. Stubbed until list provided. */
    DISP_CONTROLLED(DashboardTab.DISPENSE_QUEUE),

    /** Dispense rows whose joined drug has isHazardous = true. */
    DISP_HAZARDOUS(DashboardTab.DISPENSE_QUEUE),

    /** Inventory cycle-count batches. Deferred to iteration 2 — currently stubbed. */
    INV_CYCLE_COUNT(DashboardTab.INVENTORY_QUEUE),

    /** Inventory batches in INPROGRESS status. */
    INV_PENDING_BATCH(DashboardTab.INVENTORY_QUEUE),
}
