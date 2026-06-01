package com.rite.pillcounting.feature.dashboard.presentation.viewmodel


import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rite.pillcounting.core.room.dao.BatchDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.dao.UserDao
import com.rite.pillcounting.core.room.models.BatchEntity
import com.rite.pillcounting.core.room.models.UserEntity
import com.rite.pillcounting.core.room.models.enums.BatchStatus
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.utils.common.HelperFunctions.mapCounts
import com.rite.pillcounting.core.utils.common.HelperFunctions.secure
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.dashboard.domain.data.IUserDetailRepository
import com.rite.pillcounting.feature.dashboard.domain.model.DashboardUiState
import com.rite.pillcounting.feature.dashboard.domain.model.UserDetail
import com.rite.pillcounting.feature.hl7.core.Hl7EventHandler
import com.rite.pillcounting.feature.hl7.core.Hl7ServiceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

) : ViewModel() {

    /** Logger instance for this ViewModel. */
    private val logger = AppLogger.create<DashboardViewModel>()

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
            observeDashboardCounts()
            observeBatchCount()
            observeCompletedBatchCount()
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
