package com.dispensesure.retail.feature.menu.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dispensesure.retail.core.faceAuth.logic.SessionLockController
import com.dispensesure.retail.core.room.dao.StockBatchDao
import com.dispensesure.retail.core.room.dao.DispenseTxnDao
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.logger.LogEvent
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import com.dispensesure.retail.feature.menu.domain.model.MenuUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Menu screen.
 *
 * ### Responsibilities
 * - Count unsynced dispenses and batches via [DispenseTxnDao] and [StockBatchDao].
 * - Expose reactive [StateFlow] for UI consumption.
 *
 * Threading:
 * - Collects database flows on the ViewModel's coroutine scope.
 * - Errors are caught and replaced with a default empty state.
 */
@HiltViewModel
class MenuViewModel @Inject constructor(
    private val dispenseTxnDao: DispenseTxnDao,
    private val stockBatchDao: StockBatchDao,
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
        observeUnsyncedTransactionCount()
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

    private fun observeUnsyncedTransactionCount() {
        viewModelScope.launch {
            combine(
                dispenseTxnDao.getTotalCompletedTransactionCount(),
                stockBatchDao.getUnsyncedCompletedBatchCount()
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

