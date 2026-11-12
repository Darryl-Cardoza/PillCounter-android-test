package com.dispensesure.retail.feature.history.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dispensesure.retail.core.audit.AuditLogger
import com.dispensesure.retail.core.audit.model.HistoryDetailViewed
import com.dispensesure.retail.core.audit.model.HistoryTxnDeleted
import com.dispensesure.retail.core.room.dao.DispenseTxnDao
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.logger.LogEvent
import com.dispensesure.retail.feature.history.domain.model.HistoryDetailsUiState
import com.dispensesure.retail.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for History screen.
 * Prepares all data needed for UI, including formatted timestamps.
 */

@HiltViewModel
class HistoryDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val dispenseTxnDao: DispenseTxnDao,
    private val auditLogger: AuditLogger
) : ViewModel() {

    private val logger = AppLogger.create<HistoryDetailsViewModel>()

    // The dispense txn the screen shows, from the route.
    private val txnId: Long = savedStateHandle.get<Long>(Screen.HistoryDetail.ARG_TXN_ID) ?: 0L

    private val _uiState = MutableStateFlow(HistoryDetailsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        getTransactionDetails()
    }

    private fun getTransactionDetails() {
        viewModelScope.launch {
            try {
                val txnInfo = dispenseTxnDao.getDispenseTxnDetail(txnId)
                if (txnInfo != null) auditLogger.log(HistoryDetailViewed(txnId))
                _uiState.update { currentState ->
                    currentState.copy(
                        txnInfo = txnInfo,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("Failed to load transaction details", e, event = LogEvent.HISTORY_LOAD_FAILED)
            }
        }
    }

    fun deleteTransaction() {
        viewModelScope.launch {
            try {
                val wasUnsynced = dispenseTxnDao.getById(txnId)?.isSynced != true
                dispenseTxnDao.softDelete(txnId)
                auditLogger.log(HistoryTxnDeleted(txnId = txnId, wasUnsynced = wasUnsynced))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("Failed to delete transaction", e, event = LogEvent.HISTORY_DELETE_FAILED)
            }
        }
    }

}
