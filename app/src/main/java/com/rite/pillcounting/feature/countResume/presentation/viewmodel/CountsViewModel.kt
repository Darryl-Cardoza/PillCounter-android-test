package com.rite.pillcounting.feature.countResume.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDetailsDao
import com.rite.pillcounting.core.room.models.enums.CountStatus
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.toFormattedDate
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.countResume.domain.data.FixedCountsEvent
import com.rite.pillcounting.feature.countResume.domain.data.NavigationEvent
import com.rite.pillcounting.feature.countResume.domain.data.RegularCountsEvent
import com.rite.pillcounting.feature.countResume.domain.model.CountItem
import com.rite.pillcounting.feature.countResume.domain.model.FixedCountsUiState
import com.rite.pillcounting.feature.countResume.domain.model.RegularCountsUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel to manage both Fixed and Regular pill counts.
 *
 * Responsibilities:
 * - Observe [PillCountTxnDao] for real-time updates of transactions.
 * - Split transactions into Fixed and Regular categories.
 * - Handle multi-select operations (toggle, close, delete).
 * - Map database models into lightweight [CountItem]s for UI.
 */
@HiltViewModel
class CountsViewModel @Inject constructor(
    private val pillCountTxnDao: PillCountTxnDao,
    private val preferenceHelper: PreferenceHelper,
    private val pillCountTxnDetailsDao: PillCountTxnDetailsDao
) : ViewModel() {

    /* ------------------------- State Flows ------------------------- */

    /** Backing state for fixed count transactions. */
    private val _fixedUiState = MutableStateFlow(FixedCountsUiState())

    /** Public immutable state for UI to observe. */
    val fixedUiState: StateFlow<FixedCountsUiState> = _fixedUiState.asStateFlow()

    /** Backing state for regular count transactions. */
    private val _regularUiState = MutableStateFlow(RegularCountsUiState())

    /** Public immutable state for UI to observe. */
    val regularUiState: StateFlow<RegularCountsUiState> = _regularUiState.asStateFlow()

    private val _navigationEvent = Channel<NavigationEvent>()
    val navigationEvent = _navigationEvent.receiveAsFlow()

    init {
        observeFixedCounts()
        observeRegularCounts()
    }

    /* ------------------------- Event Dispatchers ------------------------- */

    /**
     * Handle UI events for Fixed Counts section.
     */
    fun onFixedEvent(event: FixedCountsEvent) {
        when (event) {
            is FixedCountsEvent.ItemSwipedToDelete -> deleteFixedItem(event.item)
            is FixedCountsEvent.SelectItem -> toggleFixedSelection(event.item)
            FixedCountsEvent.DeleteClicked -> handleFixedDeleteClick()
            FixedCountsEvent.ToggleMultiSelectMode -> toggleFixedMultiSelectMode()
            FixedCountsEvent.CloseMultiSelectMode -> closeFixedMultiSelectMode()
            FixedCountsEvent.SelectAllClicked -> toggleSelectAllFixedTxn()
            is FixedCountsEvent.resumeTransaction -> resumeTransaction(CountType.FIXED, event.item)
            is FixedCountsEvent.ForceCompleteTransaction -> forceCompleteTransaction(event.item)
        }
    }

    /**
     * Handle UI events for Regular Counts section.
     */
    fun onRegularEvent(event: RegularCountsEvent) {
        when (event) {
            is RegularCountsEvent.ItemSwipedToDelete -> deleteRegularItem(event.item)
            is RegularCountsEvent.SelectItem -> toggleRegularSelection(event.item)
            RegularCountsEvent.DeleteClicked -> handleRegularDeleteClick()
            RegularCountsEvent.ToggleMultiSelectMode -> toggleRegularMultiSelectMode()
            RegularCountsEvent.CloseMultiSelectMode -> closeRegularMultiSelectMode()
            is RegularCountsEvent.resumeTransaction -> resumeTransaction(
                CountType.REGULAR,
                event.item
            )

            is RegularCountsEvent.ForceCompleteTransaction -> forceCompleteTransaction(event.item)
            RegularCountsEvent.SelectAllClicked -> toggleSelectAllRegularTxn()
        }
    }

    private fun toggleSelectAllRegularTxn() {
        _regularUiState.update { state ->
            val allItems = state.regularCounts
            val allSelected = allItems.size == state.selectedItems.size && allItems.isNotEmpty()

            state.copy(
                selectedItems = if (allSelected) emptyList() else allItems
            )
        }
    }

    private fun toggleSelectAllFixedTxn() {
        _fixedUiState.update { state ->
            val allItems = state.fixedCounts
            val allSelected = allItems.size == state.selectedItems.size && allItems.isNotEmpty()

            state.copy(
                selectedItems = if (allSelected) emptyList() else allItems
            )
        }
    }

    private fun forceCompleteTransaction(item: CountItem) {
        viewModelScope.launch {
            pillCountTxnDao.updateTxnStatus(item.id, CountStatus.FORCE_COMPLETED)

            val total = pillCountTxnDetailsDao.getTotalPillCountForTxn(item.id)
            val txn = pillCountTxnDao.getById(item.id) ?: return@launch
            if (total == 0) {
                return@launch
            }
            if (txn.isComingFromHL7 == true) {
                pillCountTxnDao.markCompletedAndUnsynced(
                    txnId = txn.txnId,
                    status = txn.status
                )
            } else {
                pillCountTxnDao.updateTxnStatus(txn.txnId, txn.status)
            }
        }
    }

    /** Update Hl7 navigation here **/
    private fun resumeTransaction(countType: CountType, item: CountItem) {
        viewModelScope.launch {
            preferenceHelper.saveTxnId(item.id)
            // Always resume via DispenseScanScreen; initializeFromResumedTxn() will
            // land at PRE_NDC (container scan pending) or COUNTING (both verified).
            _navigationEvent.send(
                NavigationEvent.NavigateToDispenseScan(
                    countType = countType,
                    fromResume = true,
                )
            )
        }
    }
    /* ------------------------- Fixed Counts Logic ------------------------- */

    /**
     * Observes active transactions in DB and maps those with [CountType.FIXED].
     */
    private fun observeFixedCounts() {
        viewModelScope.launch {
            pillCountTxnDao.observePartialByCountType(CountType.FIXED, userLocalId = preferenceHelper.getLocalId(), type = StepState.TARGET_VERIFICATION)
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
                .catch { e -> e.printStackTrace() } // log & continue
                .collect { items ->
                    _fixedUiState.update { it.copy(fixedCounts = items) }
                }
        }
    }

    /**
     * Toggle selection of a [CountItem] in Fixed Counts.
     */
    private fun toggleFixedSelection(item: CountItem) {
        val selected = _fixedUiState.value.selectedItems.toMutableList()
        if (selected.contains(item)) selected.remove(item) else selected.add(item)
        _fixedUiState.update {
            it.copy(selectedItems = selected)
        }
    }

    /**
     * Handle delete button click:
     * - If multi-select active → delete selected items.
     * - Else → toggle multi-select mode.
     */
    private fun handleFixedDeleteClick() {
        if (_fixedUiState.value.isMultiSelectMode) {
            val toDelete = _fixedUiState.value.selectedItems
            viewModelScope.launch {
                toDelete.forEach { pillCountTxnDao.softDelete(it.id) }
            }
            _fixedUiState.update {
                it.copy(selectedItems = emptyList(), isMultiSelectMode = false)
            }
        } else {
            toggleFixedMultiSelectMode()
        }
    }

    /**
     * Soft delete a single Fixed Count item in DB.
     */
    private fun deleteFixedItem(item: CountItem) {
        viewModelScope.launch {
            try {
                pillCountTxnDao.softDelete(item.id)
            } catch (e: Exception) {
                if (e !is CancellationException) e.printStackTrace()
            }
        }
    }

    /** Toggle multi-select mode for Fixed Counts. */
    private fun toggleFixedMultiSelectMode() {
        _fixedUiState.update { it.copy(isMultiSelectMode = !it.isMultiSelectMode) }
    }

    /** Close multi-select mode and clear selection for Fixed Counts. */
    private fun closeFixedMultiSelectMode() {
        _fixedUiState.update { it.copy(isMultiSelectMode = false, selectedItems = emptyList()) }
    }

    /* ------------------------- Regular Counts Logic ------------------------- */

    /**
     * Observes active transactions in DB and maps those with [CountType.REGULAR].
     */
    private fun observeRegularCounts() {
        viewModelScope.launch {
            pillCountTxnDao.observePartialByCountType(countType = CountType.REGULAR, userLocalId = preferenceHelper.getLocalId(), type = StepState.TARGET_VERIFICATION)
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
                    _regularUiState.update { it.copy(regularCounts = items) }
                }
        }
    }

    /**
     * Toggle selection of a [CountItem] in Regular Counts.
     */
    private fun toggleRegularSelection(item: CountItem) {
        _regularUiState.update { state ->
            val selected = state.selectedItems.toMutableList()

            if (selected.contains(item)) selected.remove(item)
            else selected.add(item)

            state.copy(selectedItems = selected)
        }
    }


    /**
     * Handle delete button click for Regular Counts.
     */
    private fun handleRegularDeleteClick() {
        if (_regularUiState.value.isMultiSelectMode) {
            val toDelete = _regularUiState.value.selectedItems
            viewModelScope.launch {
                toDelete.forEach { pillCountTxnDao.softDelete(it.id) }
            }
            _regularUiState.update {
                it.copy(selectedItems = emptyList(), isMultiSelectMode = false)
            }
        } else {
            toggleRegularMultiSelectMode()
        }
    }

    /**
     * Soft delete a single Regular Count item in DB.
     */
    private fun deleteRegularItem(item: CountItem) {
        viewModelScope.launch {
            try {
                pillCountTxnDao.softDelete(item.id)
            } catch (e: Exception) {
                if (e !is CancellationException) e.printStackTrace()
            }
        }
    }

    /** Toggle multi-select mode for Regular Counts. */
    private fun toggleRegularMultiSelectMode() {
        _regularUiState.update { it.copy(isMultiSelectMode = !_regularUiState.value.isMultiSelectMode) }
    }

    /** Close multi-select mode and clear selection for Regular Counts. */
    private fun closeRegularMultiSelectMode() {
        _regularUiState.update { it.copy(isMultiSelectMode = false, selectedItems = emptyList()) }
    }

}
