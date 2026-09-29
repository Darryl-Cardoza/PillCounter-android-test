package com.dispensesure.retail.feature.dashboard.domain.model

/**
 * Aggregated UI state for the Dashboard screen.
 */
data class DashboardUiState(

    // ──────────────────────────────────── User / auth ────────────────────────────────────

    /** Indicates whether the user detail is currently being loaded from the server. */
    val isLoadingUserDetail: Boolean = false,

    /** Holds the error message if fetching user detail fails. Null if no error. */
    val userDetailError: String? = null,

    /** Represents the currently authenticated user's details. */
    val userDetail: UserDetail? = null,

    /**
     * Who the top bar names beside the terminal: the face user who verified into this
     * session, otherwise the logged-in account. Null until the first emission, when
     * the line falls back to [userDetail]'s own name.
     */
    val operatorName: String? = null,

    /**
     * Name of the terminal THIS install holds, as persisted by whoever last resolved
     * ownership (auth/me keyed on device_key, or the Profile screen's claim). Null when
     * this device owns no terminal — the top bar then shows no terminal segment.
     *
     * Deliberately NOT derived from `userDetail.settings.terminals` at render time:
     * that list is account-wide and every row carries `is_active = true`, so scanning it
     * for "the active one" always returned the first row (Terminal 1) on every device.
     */
    val selectedTerminalName: String? = null,

    /** Triggers navigation to Profile screen if profile details are incomplete. */
    val navigateToProfile: Boolean = false,
    val logoutUser: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,

    //set as null because when we set 0 because of observer its consider the Id and redirect to barcode screen
    val createdBatchId: Long? = null,

    /**
     * Bucket selected for a new stock-count session. Set when the user picks a bucket
     * from the Inventory quick-action dialog; consumed by the screen to navigate into
     * the inventory scan flow. The BatchEntity is created lazily on the first NDC scan
     * inside InventoryScanViewModel, not here — so an abandoned session leaves no row.
     */
    val pendingStockCountBucketId: String? = null,

    // ────────────────────────────────── New dashboard ──────────────────────────────────

    /** Pending dispense transactions for the Dispense Queue, narrowed by a dispense KPI filter. */
    val dispenseQueue: List<QueueItem.Dispense> = emptyList(),

    /** In-progress batches for the Inventory Queue, oldest first, narrowed by an inventory KPI filter. */
    val inventoryQueue: List<QueueItem.Inventory> = emptyList(),

    /** Counts in the Dispense / Inventory tab headers; they follow the active KPI filter. */
    val dispenseTabCount: Int = 0,
    val inventoryTabCount: Int = 0,

    /**
     * Counts displayed on the 5 KPI shortcut cards. Dispense counts from the unfiltered
     * dispense list, inventory counts from a count query.
     */
    val kpiCounts: Map<KpiFilter, Int> = emptyMap(),

    /**
     * KPI cards not available in standalone mode (Disp. High Priority, Inv. Cycle Count —
     * both depend on PMS-supplied data that standalone pharmacies never receive).
     */
    val disabledKpiFilters: Set<KpiFilter> = emptySet(),

    /** Currently active KPI filter, or null when "all" is selected. */
    val activeKpiFilter: KpiFilter? = null,

    /** Which of the three tabs is showing. */
    val activeTab: DashboardTab = DashboardTab.DISPENSE_QUEUE,

    /**
     * Recent Activity list (completed dispense txns + completed batches),
     * sorted descending by date. Loaded only when [activeTab] is RECENT_ACTIVITY
     * to avoid unnecessary DB work on first paint.
     */
    val recentActivity: List<QueueItem> = emptyList(),

    /** Loading state for queue/transaction fetches from database. */
    val isLoadingQueue: Boolean = false,
)
