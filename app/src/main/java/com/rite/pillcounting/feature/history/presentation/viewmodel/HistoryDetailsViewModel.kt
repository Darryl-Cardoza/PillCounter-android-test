package com.rite.pillcounting.feature.history.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.history.domain.model.HistoryDetailsUiState
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

    private val _uiState = MutableStateFlow(HistoryDetailsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        getTransactionDetails()
    }

    private fun getTransactionDetails() {
        viewModelScope.launch {
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
        }
    }

    fun deleteTransaction() {
        viewModelScope.launch {
            try {
                pillCountTxnDao.softDelete(preferenceHelper.getTxnId())
            } catch (e: Exception) {
                if (e !is CancellationException) e.printStackTrace()
            }
        }
    }

    fun getCurrentUser(): String =
        preferenceHelper.getRecentLogins().firstOrNull()
            ?: preferenceHelper.getUserId()
            ?: "—"
}





