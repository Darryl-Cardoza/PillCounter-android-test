package com.rite.pillcounting.feature.unsyncedTransaction.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.toFormattedDate
import com.rite.pillcounting.feature.countResume.domain.model.CountItem
import com.rite.pillcounting.feature.unsyncedTransaction.domain.model.UnsyncedTransactionUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UnsyncedTransactionViewModel @Inject constructor(
    private val pillCountTxnDao: PillCountTxnDao
) : ViewModel() {

    /** Backing state for fixed count transactions. */
    private val _unsyncedTransactionUiState = MutableStateFlow(UnsyncedTransactionUiState())

    /** Public immutable state for UI to observe. */
    val unsyncedTransactionUiState: StateFlow<UnsyncedTransactionUiState> =
        _unsyncedTransactionUiState.asStateFlow()

    init {
        observeUnsyncedTransaction()
    }

    private fun observeUnsyncedTransaction() {
        viewModelScope.launch {
            pillCountTxnDao.observeUnsyncedHl7Txn()
                .map { txns ->
                    txns.map {
                        CountItem(
                            id = it.txnId,
                            name = it.drugName ?: "",
                            ndc = it.ndc,
                            drugType = it.drugType,
                            bucketId = it.bucketId,
                            pillCount = it.totalPillCount,
                            target = it.targetCount ?: 0,
                            barcodeImage = it.barcodeImage,
                            date = it.createdAt.toFormattedDate(),
                            isComingFromHL7 = it.isComingFromHL7,
                            isNdcVerified = it.isNdcVerified
                        )
                    }
                }
                .catch { e -> e.printStackTrace() }
                .collect { items ->
                    _unsyncedTransactionUiState.update { it.copy(unsyncedTransactionList = items) }
                }
        }
    }

}