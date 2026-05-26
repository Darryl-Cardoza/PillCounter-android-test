package com.rite.pillcounting.feature.dashboard.presentation.viewmodel


import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rite.pillcounting.core.room.dao.BatchDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.dao.UserDao
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.room.models.BatchEntity
import com.rite.pillcounting.core.room.models.UserEntity
import com.rite.pillcounting.core.room.models.dtos.BatchSummaryDto
import com.rite.pillcounting.core.room.models.dtos.PillCountWithDrugAndTotal
import com.rite.pillcounting.core.room.models.enums.BatchStatus
import com.rite.pillcounting.core.room.models.enums.CountStatus
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.utils.common.HelperFunctions.mapCounts
import com.rite.pillcounting.core.utils.common.HelperFunctions.secure
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.dashboard.domain.data.IUserDetailRepository
import com.rite.pillcounting.feature.dashboard.domain.model.DashboardTab
import com.rite.pillcounting.feature.dashboard.domain.model.DashboardUiState
import com.rite.pillcounting.feature.dashboard.domain.model.KpiFilter
import com.rite.pillcounting.feature.dashboard.domain.model.QueueItem
import com.rite.pillcounting.feature.dashboard.domain.model.UserDetail
import com.rite.pillcounting.feature.hl7.core.Hl7EventHandler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * ViewModel for the Dashboard screen.
 *
 * ### Responsibilities
 * - Observe pill count transaction statistics from [PillCountTxnDao].
 * - Map database counts into dashboard-friendly values (Fixed/Regular, Completed/Partial).
 * - Fetch user profile details from [IUserDetailRepository] using an access token.
 * - Persist user details into Room via [UserDao].
 * - Keep preferences ([PreferenceHelper]) up-to-date with userId and localId.
 * - Expose navigation flag when profile is incomplete (to redirect user to Profile screen).
 *
 * ### Threading
 * - Database operations are executed on `Dispatchers.IO`.
 * - Results are mapped and posted to UI state using [MutableStateFlow].
 *
 * ### Logging
 * - All lifecycle and error events are logged using [AppLogger].
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val userDetailRepository: IUserDetailRepository,
    private val preferenceHelper: PreferenceHelper,
    private val userDao: UserDao,
    private val batchDao: BatchDao,
    private val pillCountTxnDao: PillCountTxnDao,
    private val hl7EventHandler: Hl7EventHandler

) : ViewModel() {

    /** Logger instance for this ViewModel. */
    private val logger = AppLogger.create<DashboardViewModel>()

    /**
     * Unfiltered combined queue, kept separate so KPI filter toggling can re-derive the
     * visible queue locally without waiting for the upstream DB flow to re-emit.
     */
    private val _unfilteredQueue = MutableStateFlow<List<QueueItem>>(emptyList())

    /** Backing state flow for the Dashboard UI. */
    private val _uiState = MutableStateFlow(DashboardUiState())

    /** Public immutable UI state exposed to the UI layer. */
    val uiState = _uiState.asStateFlow()
    val isConnected: StateFlow<Boolean> = hl7EventHandler.connectionState

    /** StateFlow to signal when terminal info is loaded from auth/me */
    private val _terminalInfoLoaded = MutableStateFlow(false)
    val terminalInfoLoaded: StateFlow<Boolean> = _terminalInfoLoaded.asStateFlow()

    init {
        logger.i("DashboardViewModel initialized.")
        //  To avoid initial observe count call because of absence of localId
        if (preferenceHelper.getLocalId() != 0.toLong()) {
            observeDashboardCounts()
            observeBatchCount()
            observeCompletedBatchCount()
            observeQueue()
        }
        fetchUserDetail()
    }

    fun isHl7Enabled(): Boolean {
        return preferenceHelper.isHl7Enabled()
    }

    fun getBucketList(): List<String> = preferenceHelper.getBucketList()

    suspend fun getLastInProgressBatch() = batchDao.getLatest()

    /**
     * Observe aggregated transaction counts and update the dashboard UI state.
     *
     * Counts are grouped by [CountType] and [CountStatus] (Completed/Partial).
     * Uses [mapCounts] to transform database rows into strongly typed buckets.
     */
    private fun observeDashboardCounts(localId: Long = preferenceHelper.getLocalId()) {
        viewModelScope.launch(Dispatchers.IO) {
            pillCountTxnDao.observeDashboardCountsGrouped(localId)
                .collect { rows ->
                    val counts = mapCounts(rows)
                    _uiState.update {
                        it.copy(
                            completedFixedCount = counts.fixedCompleted.toString(),
                            partialFixedCount = counts.fixedPartial.toString(),
//                            completedRegularCount = counts.regularCompleted.toString(),
//                            partialRegularCount = counts.regularPartial.toString()
                        )
                    }
                }
        }
    }

    private fun observeBatchCount() {
        viewModelScope.launch(Dispatchers.IO) {
            batchDao.observeActiveInProgressCount().collect { count ->
                _uiState.update {
                    it.copy(partialRegularCount = count.toString())
                }
            }
        }
    }

    private fun observeCompletedBatchCount() {
        viewModelScope.launch(Dispatchers.IO) {
            batchDao.observeCompletedBatchCount().collect { count ->
                _uiState.update {
                    it.copy(completedRegularCount = count.toString())
                }
            }
        }
    }

    // ─────────────────────────── New dashboard: queue + KPIs ───────────────────────────

    /**
     * Observes the merged "Today's Queue" — pending dispense transactions plus in-progress
     * batches — and recomputes KPI card counts whenever either source changes.
     *
     * Sources are intentionally limited to **existing DAO queries** in this iteration. Fields
     * that require new joins (hazardous / controlled / high-priority flags on dispense rows,
     * batch typing for cycle-count vs pending) are stubbed `false` / `0` here and tracked in
     * `HOMESCREEN_REDESIGN.md` under "Open questions".
     */
    private fun observeQueue(localId: Long = preferenceHelper.getLocalId()) {
        viewModelScope.launch(Dispatchers.IO) {
            val dispenseFlow = pillCountTxnDao.observePartialByCountType(
                countType = CountType.FIXED,
                partialStatus = CountStatus.PARTIAL,
                userLocalId = localId,
                type = StepState.SCAN,
            )
            val inventoryFlow = batchDao.getAllInProgress()

            dispenseFlow.combine(inventoryFlow) { dispense, batches ->
                val dispenseItems = dispense.map { txn ->
                    QueueItem.Dispense(
                        txn = txn,
                        // TODO: requires DrugMasterEntity.isHazardous on the JOIN — Turn 2 will extend the query.
                        isHazardous = false,
                        // TODO: requires `priority` column on PillCountTxnEntity (open question in HOMESCREEN_REDESIGN.md).
                        isHighPriority = false,
                        // TODO: requires controlled drugType allowlist (open question in HOMESCREEN_REDESIGN.md).
                        isControlled = false,
                    )
                }
                val inventoryItems = batches.map { b ->
                    QueueItem.Inventory(
                        batch = BatchSummaryDto(
                            batchId = b.batchId,
                            createdAt = b.startDateTime,
                            // uniqueNdcCount is unavailable from getAllInProgress(); Turn 2 query will provide it.
                            uniqueNdcCount = 0,
                            status = b.status.name,
                            bucketId = b.bucketId,
                            requestIdFromPMS = b.requestIdFromPMS,
                        )
                    )
                }
                (dispenseItems + inventoryItems).sortedBy { it.createdAt }
            }.collect { combined ->
                _unfilteredQueue.value = combined
                val counts = computeKpiCounts(combined)
                _uiState.update { state ->
                    state.copy(
                        queue = applyKpiFilter(combined, state.activeKpiFilter),
                        kpiCounts = counts,
                    )
                }
            }
        }
    }

    /** Toggle a KPI filter — tapping the same card clears it. */
    fun onKpiFilterTapped(filter: KpiFilter) {
        _uiState.update { state ->
            val newFilter = if (state.activeKpiFilter == filter) null else filter
            state.copy(
                activeKpiFilter = newFilter,
                queue = applyKpiFilter(_unfilteredQueue.value, newFilter),
            )
        }
    }

    /** Switch the active tab between Today's Queue and Recent Activity. */
    fun onTabSelected(tab: DashboardTab) {
        _uiState.update { it.copy(activeTab = tab) }
        if (tab == DashboardTab.RECENT_ACTIVITY) {
            loadRecentActivity()
        }
    }

    private var recentActivityJob: Job? = null

    /**
     * Observes completed dispense transactions + completed batches over the last 30 days,
     * merged and sorted newest-first. Started lazily the first time the user selects the
     * Recent Activity tab; re-collecting is a no-op because flows are hot-shared via Room.
     */
    private fun loadRecentActivity(localId: Long = preferenceHelper.getLocalId()) {
        if (recentActivityJob?.isActive == true) return
        if (localId == 0L) return

        val zone = ZoneId.systemDefault()
        val endMillis = LocalDate.now().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val startMillis = LocalDate.now().minusDays(RECENT_ACTIVITY_WINDOW_DAYS)
            .atStartOfDay(zone).toInstant().toEpochMilli()

        recentActivityJob = viewModelScope.launch(Dispatchers.IO) {
            val completedDispenseFlow = pillCountTxnDao.getTransactionsForDateRange(
                startDate = startMillis,
                endDate = endMillis,
                stepType = StepState.TARGET_VERIFICATION,
                type = CountType.FIXED,
                status = CountStatus.COMPLETED,
                userLocalId = localId,
            )
            val completedBatchesFlow = batchDao.getBatchSummaries(
                startDate = startMillis,
                endDate = endMillis,
                userLocalId = localId,
            )

            completedDispenseFlow.combine(completedBatchesFlow) { dispenses, batches ->
                val dispenseItems = dispenses.map { t ->
                    QueueItem.Dispense(
                        txn = PillCountWithDrugAndTotal(
                            txnId = t.txnId,
                            drugName = t.drugName,
                            ndc = t.ndc,
                            drugType = t.drugType,
                            bucketId = t.bucketId,
                            createdAt = t.createdAt,
                            targetCount = t.targetCount,
                            barcodeImage = t.barcodeImage,
                            totalPillCount = t.pillCount ?: 0,
                            isComingFromHL7 = false,
                            isNdcVerified = false,
                            countType = t.countType,
                            priority = null,
                        ),
                        isHazardous = false,
                        isHighPriority = false,
                        isControlled = false,
                    )
                }
                val inventoryItems = batches
                    .filter { it.status == BatchStatus.COMPLETED.name }
                    .map { QueueItem.Inventory(batch = it) }
                (dispenseItems + inventoryItems).sortedByDescending { it.createdAt }
            }.collect { combined ->
                _uiState.update { it.copy(recentActivity = combined) }
            }
        }
    }

    private fun computeKpiCounts(items: List<QueueItem>): Map<KpiFilter, Int> {
        val dispense = items.filterIsInstance<QueueItem.Dispense>()
        val inventory = items.filterIsInstance<QueueItem.Inventory>()
        return mapOf(
            KpiFilter.DISP_HIGH_PRIORITY to dispense.count { it.isHighPriority },
            KpiFilter.DISP_PENDING to dispense.size,
            KpiFilter.DISP_CONTROLLED to dispense.count { it.isControlled },
            KpiFilter.DISP_HAZARDOUS to dispense.count { it.isHazardous },
            // TODO: split inventory into cycle-count vs pending once BatchEntity has batchType.
            KpiFilter.INV_CYCLE_COUNT to 0,
            KpiFilter.INV_PENDING_BATCH to inventory.size,
        )
    }

    private fun applyKpiFilter(
        items: List<QueueItem>,
        filter: KpiFilter?,
    ): List<QueueItem> = when (filter) {
        null -> items
        KpiFilter.DISP_HIGH_PRIORITY -> items.filter { it is QueueItem.Dispense && it.isHighPriority }
        KpiFilter.DISP_PENDING -> items.filterIsInstance<QueueItem.Dispense>()
        KpiFilter.DISP_CONTROLLED -> items.filter { it is QueueItem.Dispense && it.isControlled }
        KpiFilter.DISP_HAZARDOUS -> items.filter { it is QueueItem.Dispense && it.isHazardous }
        KpiFilter.INV_CYCLE_COUNT -> emptyList() // see TODO above
        KpiFilter.INV_PENDING_BATCH -> items.filterIsInstance<QueueItem.Inventory>()
    }

    /**
     * Fetch the latest user details from the remote repository.
     *
     * - Reads the access token from [PreferenceHelper].
     * - Requests user details via [IUserDetailRepository].
     * - Persists the user profile into [UserDao].
     * - Saves `userId` and `localId` into [PreferenceHelper] for later use.
     * - Updates [DashboardUiState] with either success or error state.
     * - Sets [DashboardUiState.navigateToProfile] to `true` if profile is incomplete.
     */
    private fun fetchUserDetail() {
        viewModelScope.launch(Dispatchers.IO) {
            logger.d("Starting fetchUserDetail()")

            val token = preferenceHelper.getAccessToken()
            if (token.isNullOrBlank()) {
                logger.e("Access token not found in preferences.")
                _uiState.update {
                    it.copy(
                        isLoadingUserDetail = false,
                        userDetailError = "Access token not found"
                    )
                }
                return@launch
            }

            logger.i("Access token retrieved. Requesting user detail from repository.")
            _uiState.update { it.copy(isLoadingUserDetail = true, userDetailError = null) }

            val result = userDetailRepository.getUserDetail(token)
            result.fold(
                onSuccess = { payload ->
                    logger.i("User detail fetch successful. Persisting to Room...")
                    try {
                        val uiUser: UserDetail? = payload.data
                        uiUser?.let { detail ->
                            val entity = detail.toUserEntity(jwtUserId = uiUser.profile?.userId)
                            val localId = userDao.upsertPreservingLocalId(user = entity)
                            preferenceHelper.saveUserId(entity.userId)
                            preferenceHelper.setKeyBucketList(payload.data?.profile?.bucket ?: emptyList())
                            
                            // Save terminals to SharedPreferences
                            detail.terminals?.let { terminals ->
                                preferenceHelper.saveTerminals(terminals)
                                logger.i("Saved ${terminals.size} terminals to preferences")

                                // Find and save the active terminal
                                val activeTerminal = terminals.firstOrNull { it.isActive == true }
                                if (activeTerminal != null) {
                                    activeTerminal.terminalId?.let { id ->
                                        preferenceHelper.saveSelectedTerminalId(id)
                                    }
                                    activeTerminal.terminalName?.let { name ->
                                        preferenceHelper.saveSelectedTerminalName(name)
                                    }
                                    logger.i("Active terminal found and saved: ${activeTerminal.terminalName} (ID: ${activeTerminal.terminalId})")
                                } else {
                                    logger.w("No active terminal found in auth/me response")
                                }

                                // Signal that terminal info is loaded
                                _terminalInfoLoaded.value = true
                                logger.i("Terminal info loaded signal sent - HL7 can now start")
                            }
                            
                            //  To call observe count for first time when localId is 0 (from preference)
                            if (preferenceHelper.getLocalId() == 0.toLong()) {
                                observeDashboardCounts(localId)
                                observeBatchCount()
                                observeCompletedBatchCount()
                                observeQueue(localId)
                            }
                            preferenceHelper.saveLocalId(localId)
                            logger.i("User persisted locally with localId=$localId")
                        }

                        // Check if profile is incomplete
                        val isProfileIncomplete = uiUser?.profile?.isProfileCompleted == false

                        _uiState.update {
                            it.copy(
                                userDetail = uiUser,
                                isLoadingUserDetail = false,
                                userDetailError = null,
                                navigateToProfile = isProfileIncomplete
                            )
                        }
                    } catch (dbErr: Throwable) {
                        logger.e("Persisting user detail failed.", dbErr)
                        _uiState.update {
                            it.copy(
                                isLoadingUserDetail = false,
                                userDetailError = dbErr.message ?: "Failed to persist user detail"
                            )
                        }
                    }
                },
                onFailure = { error ->
                    logger.e("Failed to fetch user details.", error)
                    if (error.message == "LOGOUT") {
                        _uiState.update {
                            it.copy(
                                logoutUser = true,
                                isLoadingUserDetail = false
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                userDetail = null,
                                isLoadingUserDetail = false,
                                userDetailError = error.message ?: "An unknown error occurred"
                            )
                        }
                    }
                }
            )
        }
    }

    /** Resets the navigateToProfile flag after navigation. */
    fun resetNavigateToProfile() {
        _uiState.update { it.copy(navigateToProfile = false) }
    }

    fun saveTxnId() {
        preferenceHelper.saveTxnId(0)
    }

    /** Persists the tapped txnId so HistoryDetail can pick it up (mirrors HistoryViewModel.selectCurrentTransaction). */
    fun selectCurrentTransaction(txnId: Long) {
        preferenceHelper.saveTxnId(txnId)
    }

    fun createBatch(bucketId: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    error = null
                )
            }

            try {
                val batch = BatchEntity(
                    batchId = System.currentTimeMillis(),
                    startDateTime = System.currentTimeMillis(),
                    endDateTime = null, // Will be set when batch is completed
                    status = BatchStatus.INPROGRESS,
                    isDeleted = false,
                    note = null, // Can be set later by user
                    bucketId = bucketId // Can be set later
                )
                val batchId = batchDao.insert(batch)

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        createdBatchId = batchId
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to create batch"
                    )
                }
            }
        }
    }

    fun clearCreatedBatchId() {
        _uiState.update {
            it.copy(createdBatchId = null)
        }
    }

    private companion object {
        const val RECENT_ACTIVITY_WINDOW_DAYS = 30L
    }
}

/* ───────────────────────────── Mappers ───────────────────────────── */

/**
 * Map API payload [UserDetail] to persistence [UserEntity].
 * Uses [jwtUserId] (from JWT) as the Room primary key; falls back to email if missing.
 */
private fun UserDetail.toUserEntity(jwtUserId: String?): UserEntity {
    val pk = jwtUserId ?: this.profile?.email.orEmpty()
    return UserEntity(
        userId = pk,
        email = this.profile?.email?.secure(),
        fName = this.profile?.fName,
        lName = this.profile?.lName,
        phoneNumber = this.profile?.phoneNumber?.secure(),
        avatarUrl = this.profile?.avatarUrl,
        role = this.profile?.role?.name,
        isVerified = this.profile?.isVerified ?: false,
        isProfileCompleted = this.profile?.isProfileCompleted,
        pharmacyName = this.profile?.pharmacyName,
        npiId = this.profile?.npiId,
        language = this.settings?.language,
        timezone = this.settings?.timezone,
        notifications = this.settings?.notificationsEnabled,
        createdAt = System.currentTimeMillis()
    )
}
