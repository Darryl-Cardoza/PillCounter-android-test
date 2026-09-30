package com.dispensesure.retail.feature.menu.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dispensesure.retail.core.faceAuth.logic.SessionLockController
import com.dispensesure.retail.core.room.dao.BatchDao
import com.dispensesure.retail.core.room.dao.PillCountTxnDao
import com.dispensesure.retail.core.room.models.BatchEntity
import com.dispensesure.retail.core.room.models.enums.BatchStatus
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.logger.LogEvent
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import com.dispensesure.retail.core.utils.common.HelperFunctions.mapCounts
import com.dispensesure.retail.feature.menu.domain.model.MenuUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Menu screen.
 *
 * ### Responsibilities
 * - Listen for dashboard count changes via [PillCountTxnDao].
 * - Map grouped database rows into [MenuUiState] counts.
 * - Expose reactive [StateFlow] for UI consumption.
 *
 * Threading:
 * - Collects database flows on the ViewModel's coroutine scope.
 * - Errors are caught and replaced with a default empty state.
 */
@HiltViewModel
class MenuViewModel @Inject constructor(
    private val pillCountTxnDao: PillCountTxnDao,
    private val batchDao: BatchDao,
    private val preferenceHelper: PreferenceHelper,
    private val sessionLockController: SessionLockController
) : ViewModel() {

    /** Backing state flow for the Menu UI. */
    private val _uiState = MutableStateFlow(MenuUiState())

    /** Public immutable UI state exposed to the UI layer. */
    val uiState: StateFlow<MenuUiState> = _uiState.asStateFlow()

    /** Whether the Menu "Lock Screen" row should be enabled — mirrors [SessionLockController.hasEnabledProfile]. */
    val hasEnabledFaceProfile: StateFlow<Boolean> = sessionLockController.hasEnabledProfile

    private val logger = AppLogger.create<MenuViewModel>()

    init {
        observeDashboardCounts()
        observeBatchCount()
        observeCompletedBatchCount()
        observeUnsyncedTransactionCount()
    }

    /**
     * Observe pill count dashboard rows from DB and map into [MenuUiState].
     *
     * Uses [mapCounts] to ensure consistent logic across dashboard and menu screens.
     * Provides:
     * - Fixed Completed / Partial
     */
    private fun observeDashboardCounts() {
        viewModelScope.launch {
            pillCountTxnDao.observeDashboardCountsGrouped(preferenceHelper.getLocalId())
                .map { rows -> mapCounts(rows) }
                .catch { e ->
                    logger.e("Failed to observe dashboard counts", e, event = LogEvent.DASHBOARD_LOAD_FAILED)
                }
                .collect { counts ->
                    _uiState.update { current ->
                        current.copy(
                            fixedCompleted = counts.fixedCompleted,
                            fixedPartial = counts.fixedPartial,
                        )
                    }
                }
        }
    }

    private fun observeBatchCount() {
        viewModelScope.launch {
            batchDao.observeActiveInProgressCount()
                .catch { e -> logger.e("Failed to observe active in-progress batch count", e, event = LogEvent.FUNCTIONALITY_ERROR) }
                .collect { count ->
                    _uiState.update { current ->
                        current.copy(regularPartial = count)
                    }
                }
        }
    }

    private fun observeCompletedBatchCount() {
        viewModelScope.launch {
            batchDao.observeCompletedBatchCount()
                .catch { e -> logger.e("Failed to observe completed batch count", e, event = LogEvent.FUNCTIONALITY_ERROR) }
                .collect { count ->
                    _uiState.update { current ->
                        current.copy(regularCompleted = count)
                    }
                }
        }
    }

    fun getSavedHistoryOption(): Int {
        return preferenceHelper.getHistoryRetention()
    }

    fun getBucketList(): List<String> = preferenceHelper.getBucketList()

    /** HL7 toggle from the portal (cached in prefs). */
    fun isHl7Enabled(): Boolean = preferenceHelper.isHl7Enabled()

    /**
     * Manually triggers the session-lock overlay.
     *
     * @return true if the lock engaged, false if there's no enabled face profile to verify against.
     */
    fun lockSessionNow(): Boolean = sessionLockController.lockNow()

    suspend fun getLastInProgressBatch() = batchDao.getLatest()

    suspend fun createBatch(bucketId: String): Long? {
        return try {
            val batch = BatchEntity(
                batchId = System.currentTimeMillis(),
                startDateTime = System.currentTimeMillis(),
                endDateTime = null,
                status = BatchStatus.INPROGRESS,
                isDeleted = false,
                note = null,
                bucketId = bucketId
            )
            batchDao.insert(batch)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e("Failed to create batch", e, event = LogEvent.BATCH_CREATE_FAILED)
            null
        }
    }

    private fun observeUnsyncedTransactionCount() {
        viewModelScope.launch {
            combine(
                pillCountTxnDao.getTotalCompletedTransactionCount(),
                batchDao.getUnsyncedCompletedBatchCount()
            ) { dispenseCount, batchCount -> dispenseCount + batchCount }
                .catch { e -> logger.e("Failed to observe unsynced transaction count", e, event = LogEvent.FUNCTIONALITY_ERROR) }
                .collect { count ->
                    _uiState.update { current ->
                        current.copy(unsyncedTransactionCount = count)
                    }
                }
        }
    }

}

