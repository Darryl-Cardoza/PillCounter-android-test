package com.rite.pillcounting.feature.dashboard.presentation.viewmodel


import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rite.pillcounting.core.room.dao.BatchDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.dao.UserDao
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.room.models.UserEntity
import com.rite.pillcounting.core.room.models.dtos.PillCountWithDrugAndTotal
import com.rite.pillcounting.core.scanning.domain.model.BottleInfoJson
import com.rite.pillcounting.core.room.models.enums.BatchStatus
import com.rite.pillcounting.core.room.models.enums.CountStatus
import com.rite.pillcounting.core.room.models.enums.TxnPriority
import com.rite.pillcounting.core.models.isControlledDrugType
import com.rite.pillcounting.core.utils.device.DeviceKeyProvider
import com.rite.pillcounting.core.security.DatabaseKeyProvider
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.dashboard.domain.data.IUserDetailRepository
import com.rite.pillcounting.feature.dashboard.domain.model.DashboardTab
import com.rite.pillcounting.feature.dashboard.domain.model.DashboardUiState
import com.rite.pillcounting.feature.dashboard.domain.model.KpiFilter
import com.rite.pillcounting.feature.dashboard.domain.model.QueueItem
import com.rite.pillcounting.feature.dashboard.domain.model.Terminal
import com.rite.pillcounting.feature.dashboard.domain.model.UserDetail
import com.rite.pillcounting.feature.dashboard.domain.model.UserProfile
import com.rite.pillcounting.feature.dashboard.domain.model.UserSettings
import com.rite.pillcounting.feature.hl7.core.Hl7EventHandler
import com.rite.pillcounting.feature.hl7.core.Hl7ServiceManager
import com.rite.pillcounting.feature.hl7.util.Hl7Format
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
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
    private val hl7EventHandler: Hl7EventHandler,
    private val hl7ServiceManager: Hl7ServiceManager,
    private val deviceKeyProvider: DeviceKeyProvider,

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
    val pmsCertMismatch: StateFlow<Boolean> = hl7EventHandler.pmsCertMismatch

    fun clearPmsCertPin() {
        hl7ServiceManager.clearPmsCertPin(hl7EventHandler)
    }

    /** StateFlow to signal when terminal info is loaded from auth/me */
    private val _terminalInfoLoaded = MutableStateFlow(false)
    val terminalInfoLoaded: StateFlow<Boolean> = _terminalInfoLoaded.asStateFlow()

    init {
        logger.i("DashboardViewModel initialized.")
        //  To avoid initial observe count call because of absence of localId
        if (preferenceHelper.getLocalId() != 0.toLong()) {
            observeQueue()
        }
        observeUserDetail()
        publishSelectedTerminal()
        fetchUserDetail()
    }

    /**
     * Mirror the persisted terminal selection into [uiState] so the top bar renders the
     * terminal this device actually holds. Reads the pref rather than inspecting the
     * account-wide terminals list, which cannot distinguish one install from another.
     */
    private fun publishSelectedTerminal() {
        val name = preferenceHelper.getSelectedTerminalName()
        _uiState.update { current ->
            if (current.selectedTerminalName == name) current
            else current.copy(selectedTerminalName = name)
        }
    }

    /**
     * Recomputes which KPI cards are unavailable given the current standalone-mode flag.
     * Called after [fetchUserDetail] persists the (possibly changed) flag from the server, so
     * the dashboard reflects a standalone-mode change without needing a full app restart.
     * Deliberately NOT called at init time: the flag hasn't been refreshed for this session
     * yet at that point, and [clearTokens] already resets it to false on logout, so the
     * default `emptySet()` in [DashboardUiState] is the correct value until the fetch lands.
     */
    private fun refreshDisabledKpiFilters() {
        val disabled = emptySet<KpiFilter>()
        _uiState.update { it.copy(disabledKpiFilters = disabled) }
    }

    /**
     * Room is the single source of truth for the top bar: map each cached [UserEntity]
     * (terminals come from prefs, not Room) into `uiState.userDetail`. [fetchUserDetail]
     * only writes to Room; its upsert re-emits here. No-ops until `localId` exists.
     */
    private fun observeUserDetail(localId: Long = preferenceHelper.getLocalId()) {
        if (localId == 0L) return
        viewModelScope.launch(Dispatchers.IO) {
            userDao.observeByLocalId(localId).collect { entity ->
                val detail = entity?.toUserDetail(preferenceHelper.getTerminals()) ?: return@collect
                _uiState.update { it.copy(userDetail = detail) }
            }
        }
    }

    /**
     * Re-read the terminals list from preferences into [uiState] so the top bar
     * reflects a terminal change made elsewhere (e.g. the Profile screen, which
     * persists the new selection to prefs but cannot reach this VM's state).
     * Call this when the dashboard resumes. No network fetch — Profile already
     * saved the authoritative list.
     */
    fun refreshTerminalsFromPrefs() {
        val terminals = preferenceHelper.getTerminals()
        val selectedName = preferenceHelper.getSelectedTerminalName()
        _uiState.update { current ->
            val detail = current.userDetail ?: return@update current.copy(
                selectedTerminalName = selectedName
            )
            // Only update if the held terminal or the list actually changed, to avoid
            // needless recompositions. Keyed on the persisted selection rather than
            // is_active, which the server sets on every terminal of the account.
            val unchanged = current.selectedTerminalName == selectedName &&
                detail.settings?.terminals == terminals
            if (unchanged) current
            else current.copy(
                selectedTerminalName = selectedName,
                userDetail = detail.copy(
                    settings = (detail.settings ?: UserSettings()).copy(terminals = terminals)
                )
            )
        }
    }

    fun isHl7Enabled(): Boolean {
        return preferenceHelper.isHl7Enabled()
    }

    fun isStandaloneMode(): Boolean {
        return preferenceHelper.isStandaloneMode()
    }

    fun getBucketList(): List<String> = preferenceHelper.getBucketList()

//    suspend fun getLastInProgressBatch() = batchDao.getLatest()

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
            _uiState.update { it.copy(isLoadingQueue = true) }

            val dispenseFlow = pillCountTxnDao.observePartialByIsDispense(
                isDispense = true,
                partialStatus = CountStatus.PARTIAL,
                userLocalId = localId,
                // totalPillCount sums detail rows of THIS step only. FIXED dispense
                // never writes pill counts to the SCAN step (that's the NDC barcode
                // scan); the counted pills land in TARGET_VERIFICATION for both the
                // simple and controlled FIXED flows. Passing SCAN here made every
                // queue row show "0/target". TARGET_VERIFICATION is also the step
                // the Partial Counts resume screen (CountsViewModel) reads, so the
                // queue count now matches the resume screen.
                type = StepState.TARGET_VERIFICATION,
            )
            // Use the summaries query so uniqueNdcCount is populated from the
            // join. getAllInProgress() returns bare BatchEntity rows with no
            // txn join, which left the card stuck at "0 NDCs" even after scans.
            val inventoryFlow = batchDao.observeInProgressBatchSummaries()

            dispenseFlow.combine(inventoryFlow) { dispense, batches ->
                val dispenseItems = dispense.map { txn ->
                    QueueItem.Dispense(
                        txn = txn,
                        isHazardous = txn.isHazardous,
                        isHighPriority = txn.priority == TxnPriority.High,
                        isControlled = isControlledDrugType(txn.drugType),
                    )
                }
                val inventoryItems = batches.map { b ->
                    QueueItem.Inventory(batch = b)
                }
                (dispenseItems + inventoryItems).sortedBy { it.createdAt }
            }.collect { combined ->
                _unfilteredQueue.value = combined
                val counts = computeKpiCounts(combined)
                _uiState.update { state ->
                    state.copy(
                        queue = applyKpiFilter(combined, state.activeKpiFilter),
                        kpiCounts = counts,
                        isLoadingQueue = false,
                    )
                }
            }
        }
    }

    /** Toggle a KPI filter — tapping the same card clears it. Also switches to Today's Queue, since that's the list the KPIs filter. */
    fun onKpiFilterTapped(filter: KpiFilter) {
        _uiState.update { state ->
            val newFilter = if (state.activeKpiFilter == filter) null else filter
            state.copy(
                activeKpiFilter = newFilter,
                queue = applyKpiFilter(_unfilteredQueue.value, newFilter),
                activeTab = DashboardTab.TODAYS_QUEUE,
            )
        }
    }

    /** Switch the active tab between Today's Queue and Recent Activity. */
    fun onTabSelected(tab: DashboardTab) {
        _uiState.update { state ->
            state.copy(
                activeTab = tab,
                activeKpiFilter = if (tab == DashboardTab.RECENT_ACTIVITY) null else state.activeKpiFilter,
                queue = if (tab == DashboardTab.RECENT_ACTIVITY) applyKpiFilter(_unfilteredQueue.value, null) else state.queue,
            )
        }
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

        recentActivityJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoadingQueue = true) }

            val completedDispenseFlow = pillCountTxnDao.getTransactionsForDateRange(
                startDate = 0L,
                endDate = Long.MAX_VALUE,
                stepType = StepState.TARGET_VERIFICATION,
                isDispense = true,
                status = CountStatus.COMPLETED,
                userLocalId = localId,
            )
            val completedBatchesFlow = batchDao.getBatchSummaries(
                startDate = 0L,
                endDate = Long.MAX_VALUE,
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
                            bottleInfoListJson = t.bottleInfoListJson,
                            totalPillCount = t.pillCount ?: 0,
                            isComingFromHL7 = false,
                            isNdcVerified = false,
                            isDispense = t.isDispense,
                            priority = null,
                            drugImagePath = null,
                        ),
                        isHazardous = false,
                        isHighPriority = false,
                        isControlled = isControlledDrugType(t.drugType),
                    )
                }
                val inventoryItems = batches
                    .filter { it.status == BatchStatus.COMPLETED.name }
                    .map { QueueItem.Inventory(batch = it) }
                (dispenseItems + inventoryItems).sortedByDescending { it.createdAt }
            }.collect { combined ->
                _uiState.update { it.copy(recentActivity = combined, isLoadingQueue = false) }
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
            // Cycle Count = PMS-requested inventory counts. A batch carries a PMS
            // request id (requestIdFromPMS) only when it was created from an INR^U04
            // inventory request (Hl7Repository.handleInrInventoryRequest); manually
            // started batches have it null. This is the same discriminator the
            // history / unsynced lists use to tag a batch as PMS-sourced.
            KpiFilter.INV_CYCLE_COUNT to inventory.count { it.isCycleCount },
            // Pending Batch = manually started (non-PMS) inventory batches.
            KpiFilter.INV_PENDING_BATCH to inventory.count { !it.isCycleCount },
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
        KpiFilter.INV_CYCLE_COUNT -> items.filter { it is QueueItem.Inventory && it.isCycleCount }
        KpiFilter.INV_PENDING_BATCH -> items.filter { it is QueueItem.Inventory && !it.isCycleCount }
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
                            preferenceHelper.setKeyBucketList(payload.data?.settings?.bucket ?: emptyList())
                            preferenceHelper.setHl7Enabled(entity.isHl7Enable)
                            // Persist is_standalone so the RX-scan flow knows whether it may
                            // create dispense transactions locally (without waiting on PMS/HL7)
                            // and so the dashboard can disable the PMS-dependent KPI cards.
                            preferenceHelper.setStandaloneMode(detail.settings?.isStandalone ?: false)
                            refreshDisabledKpiFilters()
                            // Persist the HL7 spec version from the server so the HL7 parser and
                            // builder resolve the correct trigger events (e.g. RDS^O13 vs RDS^O01).
                            // Falls back to the current/default version when the server omits it.
                            detail.settings?.hl7Version
                                ?.takeIf { it.isNotBlank() }
                                ?.let { preferenceHelper.saveHl7Version(it) }
                            // Persist allow_local_storage so the HL7 sync flow knows whether to
                            // delete a dispense txn once it is completed and synced with the PMS.
                            preferenceHelper.setAllowLocalStorage(detail.settings?.allowLocalStorage ?: true)
                            // Persist bypass_ssl so HL7 MLLP connections know whether to skip
                            // TLS certificate verification for this pharmacy's PMS.
                            detail.settings?.bypassSSL?.let { preferenceHelper.setBypassTlsEnabled(it) }
                            // Persist hl7_message_spec so outbound HL7 messages are composed
                            // using the sending-application format the server has configured.
                            detail.settings?.hl7MessageSpec
                                ?.takeIf { it.isNotBlank() }
                                ?.let { preferenceHelper.saveHl7Format(Hl7Format.fromSendingApplication(it)) }
                            // Persist PMS connection info so the dispenser can reach the PMS
                            // over a static IP/port when the server has configured one.
                            preferenceHelper.setUseStaticPmsConnection(detail.settings?.useStaticPmsConnection ?: false)
                            detail.settings?.pmsIP?.let { preferenceHelper.savePmsIP(it) }
                            detail.settings?.pmsPort?.toIntOrNull()?.let { preferenceHelper.savePmsPort(it) }
                            // When the server has now disallowed local storage, clean up dispense
                            // transactions that were already synced (e.g. while the flag was still
                            // true). Runs on each auth/me response, so a true → false change takes
                            // effect on the next dashboard launch / refresh.
                            cleanupSyncedTransactionsIfNotAllowed()

                            // Persist the currently-selected pharmacy type code so the
                            // profile screen's dropdown prefills from the server value.
                            detail.profile?.pharmacyType?.let { preferenceHelper.savePharmacyType(it) }

                            // Save terminals to SharedPreferences
                            detail.settings?.terminals?.let { terminals ->
                                preferenceHelper.saveTerminals(terminals)
                                logger.i("Saved ${terminals.size} terminals to preferences")

                                // Pick the terminal THIS install holds, keyed on device_key.
                                //
                                // terminals[] is account-wide: signing one account into several
                                // phones returns an identical list to each of them, and is_active
                                // marks a terminal enabled for the account — it says nothing about
                                // which device is using it. Selecting on is_active alone therefore
                                // handed every phone the same row: three devices all identified as
                                // "Terminal 1", all advertised that name over Bonjour (which
                                // deduplicated them into "Terminal 1 (2)", "(3)" …), and every
                                // dispense went out with the same MSH-4. Worse, it ran on each
                                // auth/me response, so choosing a different terminal in Profile was
                                // silently reverted on the next dashboard refresh. device_key is the
                                // only field that distinguishes this install from the others.
                                val deviceKey = runCatching { deviceKeyProvider.getDeviceKey() }
                                    .onFailure { logger.e("Could not read device key — keeping local terminal selection", it) }
                                    .getOrNull()
                                val claimedTerminal = deviceKey?.let { key ->
                                    terminals.firstOrNull { !it.deviceKey.isNullOrBlank() && it.deviceKey == key }
                                }

                                if (claimedTerminal != null) {
                                    claimedTerminal.terminalId?.let { id ->
                                        preferenceHelper.saveSelectedTerminalId(id)
                                    }
                                    claimedTerminal.terminalName?.let { name ->
                                        preferenceHelper.saveSelectedTerminalName(name)
                                    }
                                    logger.i("Terminal claimed by this device: ${claimedTerminal.terminalName} (ID: ${claimedTerminal.terminalId})")
                                } else {
                                    // Nothing is bound to this install. Leave any existing local
                                    // selection alone rather than adopting another device's
                                    // terminal — that substitution is the bug above. With no local
                                    // selection either, HL7 stays down until one is picked in
                                    // Profile, which is correct: broadcasting a terminal identity
                                    // this device does not own is what misrouted image fetches.
                                    logger.w(
                                        "No terminal claimed by this device (deviceKeyKnown=${deviceKey != null}) — " +
                                            "keeping local selection=${preferenceHelper.getSelectedTerminalName() ?: "none"}"
                                    )
                                }

                                // Push whatever selection now stands (claimed above, or the
                                // untouched local one) into uiState so the top bar matches.
                                publishSelectedTerminal()

                                // Signal that terminal info is loaded
                                _terminalInfoLoaded.value = true
                                logger.i("Terminal info loaded signal sent - HL7 can now start")
                            }
                            
                            //  To call observe count + user detail for the first time when
                            //  localId is 0 (from preference) — i.e. first-ever launch, where
                            //  the init-time observers no-opped for lack of a localId.
                            if (preferenceHelper.getLocalId() == 0.toLong()) {
                                observeQueue(localId)
                                observeUserDetail(localId)
                            }
                            preferenceHelper.saveLocalId(localId)
                            logger.i("User persisted locally with localId=$localId")

                            // Re-wrap the DB DEK under the server-issued KEK if it's a newer
                            // version than what's currently stored (no-op otherwise, since
                            // auth/me is polled repeatedly with an unchanged KEK).
                            detail.kek?.let { kekInfo ->
                                DatabaseKeyProvider.rotateKekIfNewer(preferenceHelper.getContext(), kekInfo)
                            }
                        }

                        // Check if profile is incomplete
                        val isProfileIncomplete = uiUser?.profile?.isProfileCompleted == false

                        // userDetail is intentionally NOT set here: Room is the single source
                        // of truth (see [observeUserDetail]). The upsert above re-emits and
                        // updates the top bar. If the server returned null data, no upsert
                        // happened, so the observer keeps emitting the last cached value.
                        _uiState.update { current ->
                            current.copy(
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
                        // Don't null out userDetail on transient network failures — keep the
                        // cached value hydrated from Room so the top bar stays populated.
                        _uiState.update {
                            it.copy(
                                isLoadingUserDetail = false,
                                userDetailError = error.message ?: "An unknown error occurred"
                            )
                        }
                    }
                }
            )
        }
    }

    /**
     * Removes dispense transactions already synced with the PMS when the server's
     * `allow_local_storage` flag is false (local persistence not permitted).
     *
     * Triggered from [fetchUserDetail] after the flag is persisted, so it reacts to the auth/me
     * response: a true → false change cleans up earlier synced transactions on the next dashboard
     * launch / refresh. Only **synced** transactions are deleted — unsynced ones are retained
     * until they sync (and are then deleted on the success ACK). When the flag is true this is a
     * no-op (everything is kept locally).
     */
    private suspend fun cleanupSyncedTransactionsIfNotAllowed() {
        if (preferenceHelper.isAllowLocalStorage()) {
            logger.d("allowLocalStorage=true — retaining synced transactions locally")
            return
        }
        try {
            val syncedTransactions = pillCountTxnDao.getSyncedTransactions()
            logger.d("allowLocalStorage=false — cleaning up ${syncedTransactions.size} synced transactions")

            syncedTransactions.forEach { txn ->
                val filesToDelete = mutableListOf<String>()
                BottleInfoJson.decode(txn.bottleInfoListJson).mapNotNull { it.barcodeImagePath }.forEach { filesToDelete.add(it) }
                filesToDelete.addAll(pillCountTxnDao.getTransactionDetailsImages(txn.txnId))

                // Delete transaction (cascade deletes details)
                pillCountTxnDao.deleteTransaction(txn.txnId)

                filesToDelete.forEach { path ->
                    val file = File(path)
                    if (file.exists() && !file.delete()) {
                        logger.w("Failed to delete file for synced txn ${txn.txnId}: $path")
                    }
                }
            }
        } catch (e: Exception) {
            logger.e("Error cleaning up synced transactions", e)
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

    /**
     * Stages a stock-count session for the chosen bucket. The BatchEntity is NOT
     * inserted here — it's created lazily on the first NDC scan inside
     * InventoryScanViewModel, so a user who enters the inventory screen and leaves
     * without scanning never produces an empty batch row.
     */
    fun createBatch(bucketId: String) {
        _uiState.update { it.copy(pendingStockCountBucketId = bucketId) }
    }

    fun clearCreatedBatchId() {
        _uiState.update {
            it.copy(createdBatchId = null)
        }
    }

    fun clearPendingStockCountBucketId() {
        _uiState.update { it.copy(pendingStockCountBucketId = null) }
    }

}

/* ───────────────────────────── Mappers ───────────────────────────── */

/**
 * Reverse of [toUserEntity]: build a [UserDetail] from the locally-cached [UserEntity] plus
 * the terminals list from preferences. This is the mapper for the dashboard's single source
 * of truth (see [DashboardViewModel.observeUserDetail]) — every Room emission flows through it.
 * Fields not persisted locally (role, bucket, etc.) default to null and are refreshed when the
 * network call lands.
 */
private fun UserEntity.toUserDetail(
    terminals: List<Terminal>,
): UserDetail {
    val profile = UserProfile(
        fName = this.fName,
        lName = this.lName,
        email = this.email,
        phoneNumber = this.phoneNumber,
        avatarUrl = this.avatarUrl,
        isProfileCompleted = this.isProfileCompleted,
        pharmacyName = this.pharmacyName,
        npiId = this.npiId,
        userId = this.userId,
        isVerified = this.isVerified,
    )
    val settings = UserSettings(
        notificationsEnabled = this.notifications,
        language = this.language,
        timezone = this.timezone,
        country = this.country,
        state = this.state,
        terminals = terminals,
    )
    return UserDetail(profile = profile, settings = settings)
}

/**
 * Map API payload [UserDetail] to persistence [UserEntity].
 * Uses [jwtUserId] (from JWT) as the Room primary key; falls back to email if missing.
 */
private fun UserDetail.toUserEntity(jwtUserId: String?): UserEntity {
    val pk = jwtUserId ?: this.profile?.email.orEmpty()
    return UserEntity(
        userId = pk,
        email = this.profile?.email,
        fName = this.profile?.fName,
        lName = this.profile?.lName,
        phoneNumber = this.profile?.phoneNumber,
        avatarUrl = this.profile?.avatarUrl,
        role = this.profile?.role?.name,
        isVerified = this.profile?.isVerified ?: false,
        isProfileCompleted = this.profile?.isProfileCompleted,
        pharmacyName = this.profile?.pharmacyName,
        npiId = this.profile?.npiId,
        language = this.settings?.language,
        timezone = this.settings?.timezone,
        notifications = this.settings?.notificationsEnabled,
        country = this.settings?.country,
        state = this.settings?.state,
        createdAt = System.currentTimeMillis(),
        isHl7Enable = this.settings?.isPMSIntegrated ?: false
    )
}