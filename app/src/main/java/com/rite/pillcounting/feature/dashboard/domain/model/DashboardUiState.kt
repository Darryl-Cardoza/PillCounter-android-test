package com.rite.pillcounting.feature.dashboard.domain.model

/**
 * Aggregated UI state for the Dashboard screen.
 */
data class DashboardUiState(

    // ──────────────────────────────────── User / auth ────────────────────────────────────

    /** Indicates whether the user detail is currently being loaded from the server. */
    val isLoadingUserDetail: Boolean = false,

    /** Holds the error message if fetching user detail fails. Null if no error. */
    val userDetailError: String? = null,

    /**
     * String resource id of an error to surface when the preflight `/health` probe reported
     * unhealthy so the Dashboard skipped its `/auth/me` fetch. Defence-in-depth companion to
     * the app-wide offline overlay; null in the healthy path.
     */
    val preflightErrorRes: Int? = null,

    /** Represents the currently authenticated user's details. */
    val userDetail: UserDetail? = null,

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

    /**
     * Merged queue of pending dispense transactions + in-progress inventory batches,
     * already filtered by [activeKpiFilter] when non-null, sorted ascending by date.
     */
    val queue: List<QueueItem> = emptyList(),

    /** Counts displayed on the 6 KPI shortcut cards. Computed from the unfiltered queue. */
    val kpiCounts: Map<KpiFilter, Int> = emptyMap(),

    /**
     * KPI cards not available in standalone mode (Disp. High Priority, Inv. Cycle Count —
     * both depend on PMS-supplied data that standalone pharmacies never receive).
     */
    val disabledKpiFilters: Set<KpiFilter> = emptySet(),

    /** Currently active KPI filter, or null when "all" is selected. */
    val activeKpiFilter: KpiFilter? = null,

    /** Which tab is showing — Today's Queue or Recent Activity. */
    val activeTab: DashboardTab = DashboardTab.TODAYS_QUEUE,

    /**
     * Recent Activity list (completed dispense txns + completed batches),
     * sorted descending by date. Loaded only when [activeTab] is RECENT_ACTIVITY
     * to avoid unnecessary DB work on first paint.
     */
    val recentActivity: List<QueueItem> = emptyList(),

    /** Loading state for queue/transaction fetches from database. */
    val isLoadingQueue: Boolean = false,
)
