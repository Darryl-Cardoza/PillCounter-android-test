package com.rite.pillcounting.feature.batchCount.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rite.pillcounting.core.room.dao.BatchDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.models.dtos.BatchTxnDto
import com.rite.pillcounting.feature.batchCount.domain.model.BatchDrugGroup
import com.rite.pillcounting.feature.batchCount.domain.model.BatchLotEntry
import com.rite.pillcounting.feature.hl7.data.repository.Hl7Repository
import dagger.hilt.android.lifecycle.HiltViewModel
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
    private val pillCountTxnDao: PillCountTxnDao,
    private val batchDao: BatchDao,
    private val hl7Repository: Hl7Repository
) : ViewModel() {

    private val argBatchId: Long = savedStateHandle["batch_id"] ?: 0

    /** The resolved batch ID — starts from the nav arg, falls back to latest active batch if 0. */
    private val _resolvedBatchId = MutableStateFlow(argBatchId)
    val displayBatchId: StateFlow<Long> = _resolvedBatchId.asStateFlow()

    init {
        if (argBatchId == 0.toLong()) {
            //For getting last batch info
            viewModelScope.launch {
                batchDao.getLatest()?.let { _resolvedBatchId.value = it.batchId }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val drugGroups: StateFlow<List<BatchDrugGroup>> = _resolvedBatchId
        .flatMapLatest { id ->
            if (id == 0.toLong()) flowOf(emptyList())
            else pillCountTxnDao.observeByBatchId(id).map { it.groupAndMap() }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    fun endBatch() {
        viewModelScope.launch {
            val id = _resolvedBatchId.value
            if (id != 0.toLong()) batchDao.markAsCompleted(id)
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
                BatchDrugGroup(
                    drugId = first.drugId,
                    drugName = first.drugName ?: "Unknown Drug",
                    ndc = first.ndc ?: "",
                    sealedTotal = sealedLots.sumOf { it.count },
                    openedTotal = openedLots.sumOf { it.count },
                    totalCount = sealedLots.sumOf { it.count } + openedLots.sumOf { it.count },
                    sealedLots = sealedLots,
                    openedLots = openedLots
                )
            }
}