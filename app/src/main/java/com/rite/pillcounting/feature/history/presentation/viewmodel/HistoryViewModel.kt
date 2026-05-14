package com.rite.pillcounting.feature.history.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rite.pillcounting.core.room.models.enums.CountStatus
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.history.data.HistoryRepository
import com.rite.pillcounting.feature.history.domain.model.HistoryDeleteFilter
import com.rite.pillcounting.feature.history.domain.model.HistoryMode
import com.rite.pillcounting.feature.history.domain.model.BatchSummary
import com.rite.pillcounting.feature.history.domain.model.ToggleOption
import com.rite.pillcounting.feature.history.domain.model.TxnWithDrugDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * ViewModel for History screen.
 * Prepares all data needed for UI, including formatted timestamps.
 */

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: HistoryRepository,
    private val preferenceHelper: PreferenceHelper
) : ViewModel() {
    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate

    private val today = LocalDate.now()

    // Range state (default = today)
    private val _startDate = MutableStateFlow(today)
    private val _endDate = MutableStateFlow(today)

    val startDate: StateFlow<LocalDate> = _startDate
    val endDate: StateFlow<LocalDate> = _endDate

    // Mode
    private val _currentMode = MutableStateFlow(HistoryMode.NORMAL)
    val currentMode: StateFlow<HistoryMode> = _currentMode

    // Search query (local filter only)
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    fun setDateRange(start: LocalDate?, end: LocalDate?) {
        _startDate.value = start
        _endDate.value = end
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /**
     * RAW DATA FROM DATABASE
     * Only triggered when:
     * - date range changes
     * - mode changes
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val rawCounts: StateFlow<List<TxnWithDrugDto>> =
        combine(_startDate, _endDate, _currentMode) { start, end, mode ->
            Triple(start, end, mode)
        }
            .flatMapLatest { (start, end, mode) ->

                val (type, status) = mode.toQueryParams()

                repository.getTransactionsForDateRange(
                    startDate = start,
                    endDate = end,
                    type = type,
                    status = status,
                    userLocalId = preferenceHelper.getLocalId()
                )
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                emptyList()
            )



    /**
     *  FINAL LIST EXPOSED TO UI
     * Search is applied locally in memory.
     */
    val counts: StateFlow<List<TxnWithDrugDto>> =
        combine(rawCounts, _searchQuery) { list, query ->

            if (query.isBlank()) {
                list
            } else {
                list.filter {
                    it.drugName?.contains(query, ignoreCase = true) == true ||
                            it.ndc?.contains(query, ignoreCase = true) == true ||
                            it.note?.contains(query, ignoreCase = true) == true
                }
            }
        }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                emptyList()
            )


    @OptIn(ExperimentalCoroutinesApi::class)
    private val rawBatchGroups: StateFlow<List<BatchSummary>> =
        combine(_startDate, _endDate) { start, end -> start to end }
            .flatMapLatest { (start, end) ->
                repository.getBatchSummaries(
                    startDate = start,
                    endDate = end,
                    userLocalId = preferenceHelper.getLocalId()
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val batchGroups: StateFlow<List<BatchSummary>> =
        combine(rawBatchGroups, _searchQuery) { list, query ->
            if (query.isBlank()) {
                list
            } else {
                list.filter {
                    it.batchId.toString().contains(query, ignoreCase = true) ||
                            it.bucketId?.contains(query, ignoreCase = true) == true ||
                            it.requestIdFromPMS?.contains(query, ignoreCase = true) == true
                }
            }
        }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setHistoryMode(mode: HistoryMode) {
        _currentMode.value = mode
    }

    fun selectDate(date: LocalDate) {
        _selectedDate.value = date
    }

    private fun HistoryMode.toQueryParams(): Pair<CountType?, CountStatus?> =
        if (this == HistoryMode.DISPENSE) CountType.FIXED to null else null to null


    fun deleteCountsForSelectedDate(option: ToggleOption, filter: HistoryDeleteFilter) {
        viewModelScope.launch {
            val isCompleted: Boolean? = when (filter) {
                HistoryDeleteFilter.ALL -> null
                HistoryDeleteFilter.COMPLETED -> true
                HistoryDeleteFilter.PENDING -> false
            }
            if (option == ToggleOption.STOCK) {
                repository.deleteBatchesForDateRange(
                    startDate = _startDate.value,
                    endDate = _endDate.value,
                    isCompleted = isCompleted,
                    userLocalId = preferenceHelper.getLocalId()
                )
            } else {
                val (type, _) = currentMode.value.toQueryParams()
                repository.deleteTransactionsForDate(
                    startDate = _startDate.value,
                    endDate = _endDate.value,
                    type = type,
                    isCompleted = isCompleted,
                    userLocalId = preferenceHelper.getLocalId()
                )
            }
        }
    }


    fun selectCurrentTransaction(txnId: Long) {
        preferenceHelper.saveTxnId(txnId)
    }
}




