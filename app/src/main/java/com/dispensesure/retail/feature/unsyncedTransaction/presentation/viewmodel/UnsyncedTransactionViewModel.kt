package com.dispensesure.retail.feature.unsyncedTransaction.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.room.dao.BatchDao
import com.dispensesure.retail.core.room.dao.PillCountTxnDao
import com.dispensesure.retail.core.room.models.dtos.PillCountWithDrugAndTotal
import com.dispensesure.retail.core.room.models.enums.BatchStatus
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.toFormattedDate
import com.dispensesure.retail.feature.countResume.domain.model.CountItem
import com.dispensesure.retail.feature.history.domain.model.BatchSummary
import com.dispensesure.retail.feature.hl7.core.Hl7EventHandler
import com.dispensesure.retail.feature.hl7.data.repository.Hl7Repository
import com.dispensesure.retail.feature.unsyncedTransaction.domain.model.UnsyncedTransactionUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UnsyncedTransactionViewModel @Inject constructor(
    private val pillCountTxnDao: PillCountTxnDao,
    private val batchDao: BatchDao,
    private val hl7Repository: Hl7Repository,
    private val hl7EventHandler: Hl7EventHandler,
) : ViewModel() {

    private val _unsyncedTransactionUiState = MutableStateFlow(UnsyncedTransactionUiState())

    val unsyncedTransactionUiState: StateFlow<UnsyncedTransactionUiState> =
        _unsyncedTransactionUiState.asStateFlow()

    init {
        observeUnsyncedDispense()
        observeUnsyncedBatches()
        syncBatchesWhenConnected()
    }

    private fun observeUnsyncedDispense() {
        viewModelScope.launch {
            pillCountTxnDao.observeUnsyncedByIsDispense(
                isDispense = true,
                type = StepState.TARGET_VERIFICATION.toString()
            )
                .map { txns -> txns.map { it.toCountItem() } }
                .catch { e -> e.printStackTrace() }
                .collect { items ->
                    _unsyncedTransactionUiState.update { it.copy(dispenseList = items) }
                }
        }
    }

    private fun observeUnsyncedBatches() {
        viewModelScope.launch {
            batchDao.observeUnsyncedCompletedBatches()
                .map { dtos ->
                    dtos.map { dto ->
                        BatchSummary(
                            batchId = dto.batchId,
                            createdAt = dto.createdAt,
                            uniqueNdcCount = dto.uniqueNdcCount,
                            status = BatchStatus.valueOf(dto.status),
                            bucketId = dto.bucketId,
                            requestIdFromPMS = dto.requestIdFromPMS
                        )
                    }
                }
                .catch { e -> e.printStackTrace() }
                .collect { batches ->
                    _unsyncedTransactionUiState.update { it.copy(batchList = batches) }
                }
        }
    }

    private fun syncBatchesWhenConnected() {
        viewModelScope.launch {
            hl7EventHandler.connectionState
                .filter { it }
                .collect {
                    hl7Repository.resendPendingHl7BatchTransactions()
                }
        }
    }

    private fun PillCountWithDrugAndTotal.toCountItem() = CountItem(
        id = txnId,
        name = drugName ?: "",
        ndc = ndc,
        drugType = drugType,
        bucketId = bucketId,
        pillCount = totalPillCount,
        target = targetCount ?: 0,
        bottleInfoListJson = bottleInfoListJson,
        date = createdAt.toFormattedDate(),
        isComingFromHL7 = isComingFromHL7,
        isNdcVerified = isNdcVerified,
        isDispense = isDispense,
        strength = strength,
        dosageForm = dosageForm,
        drugImagePath = drugImagePath,
        rxNo = rxNo,
        refillNo = refillNo
    )
}
