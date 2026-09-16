package com.rite.pillcounting.feature.batchCount.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rite.pillcounting.core.room.dao.BatchDao
import com.rite.pillcounting.core.room.dao.BottleInfoDao
import com.rite.pillcounting.core.room.dao.StockTxnDao
import com.rite.pillcounting.core.room.models.BatchEntity
import com.rite.pillcounting.core.room.models.dtos.BatchTxnDto
import com.rite.pillcounting.core.room.models.enums.BatchStatus
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.batchCount.domain.model.BatchDrugGroup
import com.rite.pillcounting.feature.batchCount.domain.model.BatchLotEntry
import com.rite.pillcounting.feature.hl7.data.repository.Hl7Repository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BatchViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val stockTxnDao: StockTxnDao,
    private val bottleInfoDao: BottleInfoDao,
    private val batchDao: BatchDao,
    private val hl7Repository: Hl7Repository,
    private val preferenceHelper: PreferenceHelper
) : ViewModel() {

    private val argBatchId: Long = savedStateHandle["batch_id"] ?: 0

    private val _resolvedBatchId = MutableStateFlow(argBatchId)
    val displayBatchId: StateFlow<Long> = _resolvedBatchId.asStateFlow()

    private val _isBatchCompleted = MutableStateFlow(false)
    val isBatchCompleted: StateFlow<Boolean> = _isBatchCompleted.asStateFlow()

    private val _batchEntity = MutableStateFlow<BatchEntity?>(null)
    val batchEntity: StateFlow<BatchEntity?> = _batchEntity.asStateFlow()

    private val _uniqueNdcCount = MutableStateFlow(0)
    val uniqueNdcCount: StateFlow<Int> = _uniqueNdcCount.asStateFlow()

    init {
        viewModelScope.launch {
            val batchId = if (argBatchId == 0L) {
                batchDao.getLatest()?.batchId?.also { _resolvedBatchId.value = it } ?: 0L
            } else {
                argBatchId
            }
            if (batchId != 0L) {
                val entity = batchDao.getById(batchId)
                _isBatchCompleted.value = entity?.status == BatchStatus.COMPLETED
                _batchEntity.value = entity
                _uniqueNdcCount.value = stockTxnDao.getUniqueNdcCountForBatch(batchId)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val drugGroups: StateFlow<List<BatchDrugGroup>> = _resolvedBatchId
        .flatMapLatest { id ->
            if (id == 0L) flowOf(emptyList())
            else bottleInfoDao.observeByBatchId(id).map { it.groupAndMap() }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    fun deleteBatch(onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                val id = _resolvedBatchId.value
                if (id != 0L) {
                    batchDao.softDelete(id)
                    stockTxnDao.deleteByBatchIds(listOf(id))
                }
            } catch (e: Exception) {
                if (e !is CancellationException) e.printStackTrace()
            } finally {
                onDone()
            }
        }
    }

    fun getCurrentUser(): String =
        preferenceHelper.getLoggedInEmail()
            ?: preferenceHelper.getUserId()
            ?: "—"

    fun endBatch(note: String? = null) {
        viewModelScope.launch {
            val id = _resolvedBatchId.value
            if (id != 0L) {
                batchDao.markAsCompleted(id)
                if (!note.isNullOrBlank()) batchDao.updateNote(id, note)
            }
            hl7Repository.buildAndSendInventoryResponse(id)
        }
    }

    private fun List<BatchTxnDto>.groupAndMap(): List<BatchDrugGroup> =
        groupBy { it.drugId }
            .map { (_, txns) ->
                val sealedLots = txns
                    .filter { (it.bottleQty ?: 0) > 0 }
                    .map { BatchLotEntry(it.lotNo, it.expiry, (it.bottleQty ?: 0) * (it.packageQty ?: 1)) }
                val openedLots = txns
                    .filter { (it.looseQty ?: 0) > 0 }
                    .map { BatchLotEntry(it.lotNo, it.expiry, it.looseQty ?: 0) }

                val first = txns.first()
                val sealedBottleQty = txns.sumOf { it.bottleQty ?: 0 }
                BatchDrugGroup(
                    drugId = first.drugId,
                    drugName = first.drugName ?: "Unknown Drug",
                    ndc = first.ndc ?: "",
                    sealedTotal = sealedLots.sumOf { it.count },
                    openedTotal = openedLots.sumOf { it.count },
                    totalCount = sealedLots.sumOf { it.count } + openedLots.sumOf { it.count },
                    sealedBottleQty = sealedBottleQty,
                    sealedLots = sealedLots,
                    openedLots = openedLots
                )
            }
}
