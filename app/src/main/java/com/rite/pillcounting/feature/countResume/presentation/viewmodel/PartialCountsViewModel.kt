package com.rite.pillcounting.feature.countResume.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rite.pillcounting.core.room.dao.BatchDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.toFormattedDate
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.feature.countResume.domain.model.PartialCountsUiState
import com.rite.pillcounting.feature.countResume.presentation.PartialBatchItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PartialCountsViewModel @Inject constructor(
    private val batchDao: BatchDao,
    private val pillCountTxnDao: PillCountTxnDao
) : ViewModel() {

    private val logger = AppLogger.create<PartialCountsViewModel>()

    private val _uiState = MutableStateFlow(PartialCountsUiState())
    val uiState: StateFlow<PartialCountsUiState> = _uiState.asStateFlow()

    init {
        logger.i("PartialCountsViewModel initialized.")
        observePartialCounts()
    }

    private fun observePartialCounts() {
        viewModelScope.launch(Dispatchers.IO) {
            batchDao.getAllInProgress()
                .flatMapLatest { batches ->
                    if (batches.isEmpty()) {
                        flowOf(emptyList())
                    } else {
                        combine(
                            batches.map { entity ->
                                pillCountTxnDao.observeTotalCountByBatchId(entity.batchId)
                                    .map { count -> entity to count }
                            }
                        ) { pairs ->
                            pairs.map { (entity, count) ->
                                PartialBatchItem(
                                    entityBatchId = entity.batchId,
                                    batchId = entity.batchId.toString(),
                                    dateTime = entity.startDateTime.toFormattedDate(),
                                    count = count.toString(),
                                    bucketId = entity.bucketId ?: "NORMAL"
                                )
                            }
                        }
                    }
                }
                .collect { items ->
                    _uiState.update {
                        it.copy(
                            batches = items,
                            isLoading = false,
                            error = null
                        )
                    }
                    logger.d("Partial batches updated: ${items.size} items")
                }
        }
    }

    fun deleteSelectedBatches(itemsToDelete: List<PartialBatchItem>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                logger.d("Deleting ${itemsToDelete.size} batches...")
                itemsToDelete.forEach { item ->
                    batchDao.softDelete(item.entityBatchId)
                    logger.i("Batch ${item.entityBatchId} deleted successfully")
                }
                logger.i("Batch deletion completed")
            } catch (e: Exception) {
                logger.e("Error deleting batches", e)
                _uiState.update { it.copy(error = e.message ?: "Failed to delete batches") }
            }
        }
    }

}