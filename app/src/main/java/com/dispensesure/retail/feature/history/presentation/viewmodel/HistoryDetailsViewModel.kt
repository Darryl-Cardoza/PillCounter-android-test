package com.dispensesure.retail.feature.history.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dispensesure.retail.core.room.dao.PillCountTxnDao
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.logger.LogEvent
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import com.dispensesure.retail.feature.history.domain.model.HistoryDetailsUiState
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
    private val preferenceHelper: PreferenceHelper,
    private val pillCountTxnDao: PillCountTxnDao
) : ViewModel() {

    private val logger = AppLogger.create<HistoryDetailsViewModel>()

    private val _uiState = MutableStateFlow(HistoryDetailsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        getTransactionDetails()
    }

    private fun getTransactionDetails() {
        viewModelScope.launch {
            try {
                val txnInfo = pillCountTxnDao.getTxnWithDetails(preferenceHelper.getTxnId())
                    ?.let { txn ->
                        // @Relation auto-query has no isDeleted filter — strip soft-deleted
                        // details here so they never reach the UI.
                        txn.copy(txnDetails = txn.txnDetails.filter { !it.isDeleted })
                    }
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
                pillCountTxnDao.softDelete(preferenceHelper.getTxnId())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("Failed to delete transaction", e, event = LogEvent.HISTORY_DELETE_FAILED)
            }
        }
    }

}





