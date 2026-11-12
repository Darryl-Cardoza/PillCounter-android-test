package com.dispensesure.retail.feature.unsyncedTransaction.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.region.RegionProvider
import com.dispensesure.retail.core.room.dao.StockBatchDao
import com.dispensesure.retail.core.room.dao.DispenseTxnDao
import com.dispensesure.retail.core.room.models.dtos.DispenseTxnWithDrug
import com.dispensesure.retail.core.room.models.enums.BatchStatus
import com.dispensesure.retail.core.utils.common.UserInterfaceUtils.toFormattedDate
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.logger.LogEvent
import com.dispensesure.retail.feature.unsyncedTransaction.domain.model.CountItem
import com.dispensesure.retail.feature.history.domain.model.BatchSummary
import com.dispensesure.retail.feature.hl7.core.Hl7EventHandler
import com.dispensesure.retail.feature.hl7.data.repository.StockHl7Repository
import com.dispensesure.retail.feature.unsyncedTransaction.domain.model.UnsyncedTransactionUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
    private val dispenseTxnDao: DispenseTxnDao,
    private val stockBatchDao: StockBatchDao,
    private val stockHl7Repository: StockHl7Repository,
    private val hl7EventHandler: Hl7EventHandler,
    private val regionProvider: RegionProvider,
) : ViewModel() {

    private val _unsyncedTransactionUiState = MutableStateFlow(UnsyncedTransactionUiState())

    val unsyncedTransactionUiState: StateFlow<UnsyncedTransactionUiState> =
        _unsyncedTransactionUiState.asStateFlow()

    private val logger = AppLogger.create<UnsyncedTransactionViewModel>()

    init {
        observeUnsyncedDispense()
        observeUnsyncedBatches()
        syncBatchesWhenConnected()
    }

    private fun observeUnsyncedDispense() {
        viewModelScope.launch {
            dispenseTxnDao.observeUnsyncedDispenses(
                region = regionProvider.current,
                step = StepState.TARGET_VERIFICATION.toString()
            )
                .map { txns -> txns.map { it.toCountItem() } }
                .catch { e -> logger.e("Failed to observe unsynced dispense transactions", e, event = LogEvent.TRANSACTION_SYNC_FAILED) }
                .collect { items ->
                    _unsyncedTransactionUiState.update { it.copy(dispenseList = items) }
                }
        }
    }

    private fun observeUnsyncedBatches() {
        viewModelScope.launch {
            stockBatchDao.observeUnsyncedCompletedBatches(regionProvider.current)
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
                .catch { e -> logger.e("Failed to observe unsynced completed batches", e, event = LogEvent.TRANSACTION_SYNC_FAILED) }
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
                    try {
                        stockHl7Repository.sendPendingBatches()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.e("Failed to resend pending HL7 batch transactions", e, event = LogEvent.HL7_RESEND_FAILED)
                    }
                }
        }
    }

    private fun DispenseTxnWithDrug.toCountItem() = CountItem(
        id = txnId,
        name = drugName ?: "",
        ndc = ndc,
        drugType = drugType,
        bucketId = bucketId,
        target = targetCount ?: 0,
        bottlesJson = bottlesJson,
        date = createdAt.toFormattedDate(regionProvider.current),
        isComingFromHL7 = isComingFromHL7,
        isNdcVerified = isNdcVerified,
        strength = strength,
        dosageForm = dosageForm,
        drugImagePath = drugImagePath,
        rxNo = rxNo,
        refillNo = refillNo
    )
}
