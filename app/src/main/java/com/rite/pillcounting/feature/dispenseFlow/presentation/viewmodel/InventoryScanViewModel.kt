package com.rite.pillcounting.feature.pillCountScan.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rite.pillcounting.R
import com.rite.pillcounting.core.room.dao.BatchDao
import com.rite.pillcounting.core.room.dao.DrugMasterDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.models.BatchEntity
import com.rite.pillcounting.core.room.models.PillCountTxnEntity
import com.rite.pillcounting.core.room.models.dtos.BatchTxnDto
import com.rite.pillcounting.core.room.models.enums.BatchStatus
import com.rite.pillcounting.core.room.models.enums.CountStatus
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.utils.common.BarcodeDecoder
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.ActiveNdc
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.BatchStockCountUiState
import com.rite.pillcounting.feature.pillCountScan.presentation.compose.RecentBatchRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * Drives the new tablet-landscape Inventory scan screen.
 *
 * Lifecycle:
 *  - On init: resolves [batchId] from SavedStateHandle and reads the parent
 *    BatchEntity once (for bucket display on the active card).
 *  - Exposes [uiState] = recent counts (Room-backed Flow, newest first) +
 *    optional active NDC (session-local) + totals.
 *
 * Scan flow:
 *  - Camera analyzer hands raw barcodes to [onBarcodeDetected].
 *  - We decode GS1 → GTIN-14 → lookup drug in drug_master.
 *  - If an existing sealed txn in this batch matches (drugId, lot, expiry),
 *    activeNdc starts with the existing bottle count so +/- tunes from there;
 *    ADD updates that existing txn.
 *  - Otherwise activeNdc starts with bottles=1; ADD inserts a new txn.
 *
 * ADD persists, CLEAR is purely in-memory. END COUNT marks the batch completed.
 *
 * SCAN PILLS is a stub here — the in-place mode toggle to the legacy
 * pill-counting UI will land in a later pass (see BATCH_STOCK_COUNT_REVAMP.md
 * §7c).
 */
@HiltViewModel
class InventoryScanViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val batchDao: BatchDao,
    private val pillCountTxnDao: PillCountTxnDao,
    private val drugMasterDao: DrugMasterDao,
    private val preferenceHelper: PreferenceHelper,
    private val barcodeDecoder: BarcodeDecoder,
) : ViewModel() {

    private val logger = AppLogger("InventoryScanViewModel")

    /**
     * Route arg. 0 when the caller hasn't created a batch yet (Inventory quick
     * action from the dashboard) — the batch is created lazily on the first
     * successful NDC scan. Non-zero when resuming an existing batch.
     */
    private val argBatchId: Long = savedStateHandle["batch_id"] ?: 0L

    /** Route arg used only when [argBatchId] is 0 — seeds the lazily-created batch. */
    private val argBucketId: String? = savedStateHandle.get<String>("bucket_id")?.ifBlank { null }

    private val _resolvedBatchId = MutableStateFlow(argBatchId)

    /** Bucket label rendered on the active card. Empty until the batch loads. */
    private val _bucketId = MutableStateFlow<String?>(null)

    /** Session-local active NDC. Null when the bottom card shows Summary. */
    private val _activeNdc = MutableStateFlow<ActiveNdc?>(null)

    /**
     * Internal flag retained for VM-side bookkeeping. No longer exposed —
     * the screen now gates the analyzer on `activeNdc` directly which is
     * more deterministic than mirroring a separate flag through a
     * LaunchedEffect.
     */
    private val _scannerPaused = MutableStateFlow(false)

    /**
     * One-shot user-facing error events. The VM emits a [LocalizedError] with
     * a string resource ID (+ optional formatArg) so the screen can resolve it
     * via stringResource — keeps the VM free of Android context.
     */
    private val _errorMessage = MutableStateFlow<LocalizedError?>(null)
    val errorMessage: StateFlow<LocalizedError?> = _errorMessage.asStateFlow()

    /** END COUNT confirmation dialog visibility. */
    private val _showEndCountDialog = MutableStateFlow(false)
    val showEndCountDialog: StateFlow<Boolean> = _showEndCountDialog.asStateFlow()

    /** Emit true once the batch has been marked completed; the screen pops back. */
    private val _batchEnded = MutableStateFlow(false)
    val batchEnded: StateFlow<Boolean> = _batchEnded.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val recentRows: StateFlow<List<RecentBatchRow>> = _resolvedBatchId
        .flatMapLatest { id ->
            if (id == 0L) flowOf(emptyList())
            else pillCountTxnDao.observeByBatchId(id)
        }
        .map { txns -> txns.toRecentRows() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val uiState: StateFlow<BatchStockCountUiState> = combine(
        recentRows,
        _activeNdc,
    ) { rows, active ->
        BatchStockCountUiState(
            recentCounts = rows,
            activeNdc = active,
            totalNdcs = rows.size,
            totalPills = rows.sumOf { it.pills },
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000L),
        BatchStockCountUiState(emptyList(), null, 0, 0),
    )

    init {
        viewModelScope.launch {
            if (argBatchId != 0L) {
                _resolvedBatchId.value = argBatchId
                _bucketId.value = batchDao.getById(argBatchId)?.bucketId
            } else {
                // No batch yet — the user selected a bucket on the dashboard but the
                // batch row will be created on the first successful NDC scan.
                _bucketId.value = argBucketId
            }
        }
    }

    /**
     * Inserts a new BatchEntity for the current bucket and stores its id. Called
     * once, on the first successful NDC scan, when the screen was entered via the
     * Inventory quick action (which intentionally defers batch creation so an
     * abandoned session never produces an empty batch row).
     *
     * Returns the new batchId on success, or 0 if the insert failed.
     */
    private suspend fun ensureBatchCreated(): Long {
        val existing = _resolvedBatchId.value
        if (existing != 0L) return existing
        return try {
            val now = System.currentTimeMillis()
            val newId = batchDao.insert(
                BatchEntity(
                    batchId = now,
                    startDateTime = now,
                    endDateTime = null,
                    status = BatchStatus.INPROGRESS,
                    isDeleted = false,
                    note = null,
                    bucketId = _bucketId.value,
                )
            )
            _resolvedBatchId.value = newId
            newId
        } catch (e: Exception) {
            logger.e("ensureBatchCreated failed", e)
            0L
        }
    }

    /* ─────────────────────────  Scanner  ───────────────────────── */

    /**
     * Called by the camera shell whenever the analyzer detects a barcode.
     * Decodes GS1, looks up the drug, and populates the active NDC card.
     *
     * If an NDC is already active when a new one is scanned, the active NDC is
     * auto-committed to the batch (same persistence path as ADD) and the new
     * NDC takes the active slot — this lets the user scan continuously without
     * tapping ADD between bottles.
     */
    fun onBarcodeDetected(rawValue: String) {
        _scannerPaused.value = true
        viewModelScope.launch {
            try {
                val isGs1 = barcodeDecoder.isGs1Barcode(rawValue)
                val decoded = if (isGs1) barcodeDecoder.decode(rawValue) else null
                val extractedGtin = if (isGs1) decoded?.gtin else barcodeDecoder.toGtin14(rawValue)
                val gtin14 = extractedGtin?.let { barcodeDecoder.toGtin14(it) }
                if (gtin14.isNullOrBlank() || gtin14.length != 14 || !gtin14.all { it.isDigit() }) {
                    _errorMessage.value = LocalizedError(R.string.batch_stock_count_invalid_label)
                    _scannerPaused.value = false
                    return@launch
                }

                val drug = drugMasterDao.getDrugByGtin(gtin14) ?: drugMasterDao.getDrugByNdc(gtin14)
                if (drug == null) {
                    _errorMessage.value = LocalizedError(R.string.batch_stock_count_drug_not_found, gtin14)
                    _scannerPaused.value = false
                    return@launch
                }

                // First valid scan in a fresh stock-count session: create the
                // BatchEntity now so abandoned sessions leave no DB row.
                if (_resolvedBatchId.value == 0L) {
                    val newBatchId = ensureBatchCreated()
                    if (newBatchId == 0L) {
                        _errorMessage.value = LocalizedError(R.string.batch_stock_count_no_active_batch)
                        _scannerPaused.value = false
                        return@launch
                    }
                }

                // If an NDC is already active, auto-commit it before switching.
                // Skip the commit when the same NDC is rescanned — we'll re-activate
                // its existing row instead.
                val currentActive = _activeNdc.value
                if (currentActive != null && currentActive.ndc != drug.ndc) {
                    persistActive(currentActive)
                }

                val lotNo = decoded?.lotNumber
                val expiry = decoded?.expirationDate?.format(DateTimeFormatter.ofPattern("MM-dd-yyyy"))
                val packageQty = drug.packageQty ?: 0

                // Look for an existing sealed txn for this (drug, lot, expiry)
                // in the current batch — same logic ScanBarcodeViewModel uses.
                val batchId = _resolvedBatchId.value
                val existing = if (batchId != 0L) {
                    pillCountTxnDao.findSealedTxnInBatch(
                        batchId = batchId,
                        drugId = drug.drugId.toString(),
                        lotNo = lotNo,
                        expiry = expiry,
                    )
                } else null

                val startBottles = (existing?.bottleQty ?: 0).coerceAtLeast(1)
                _activeNdc.value = ActiveNdc(
                    ndc = drug.ndc,
                    drugName = drug.drugName ?: "",
                    bucket = _bucketId.value.orEmpty(),
                    batchNo = lotNo.orEmpty(),
                    expiry = expiry.orEmpty(),
                    pillsPerBottle = packageQty,
                    bottles = startBottles,
                )
            } catch (e: Exception) {
                logger.e("onBarcodeDetected failed", e)
                _errorMessage.value = LocalizedError(R.string.batch_stock_count_scan_failed)
                _scannerPaused.value = false
            }
        }
    }

    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    /* ─────────────────────────  Counter  ───────────────────────── */

    fun increment() {
        _activeNdc.update { it?.copy(bottles = it.bottles + 1) }
    }

    fun decrement() {
        _activeNdc.update { it?.copy(bottles = (it.bottles - 1).coerceAtLeast(1)) }
    }

    /* ─────────────────────────  Clear / Add  ───────────────────────── */

    fun onClear() {
        _activeNdc.value = null
        _scannerPaused.value = false
    }

    /**
     * Persists the given active NDC into the current batch. Shared by ADD and
     * by the auto-commit path when a different NDC is scanned while one is
     * already active. Updates the existing sealed txn if found, otherwise
     * inserts a new one.
     */
    private suspend fun persistActive(active: ActiveNdc) {
        val batchId = _resolvedBatchId.value
        if (batchId == 0L) {
            _errorMessage.value = LocalizedError(R.string.batch_stock_count_no_active_batch)
            return
        }
        try {
            val drugId = drugMasterDao.getDrugIdByNdc(active.ndc)
            if (drugId == null) {
                _errorMessage.value = LocalizedError(R.string.batch_stock_count_drug_not_found, active.ndc)
                return
            }
            val existing = pillCountTxnDao.findSealedTxnInBatch(
                batchId = batchId,
                drugId = drugId.toString(),
                lotNo = active.batchNo.ifBlank { null },
                expiry = active.expiry.ifBlank { null },
            )
            if (existing != null) {
                pillCountTxnDao.update(
                    existing.copy(
                        bottleQty = active.bottles,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            } else {
                pillCountTxnDao.upsertPreservingId(
                    PillCountTxnEntity(
                        localId = preferenceHelper.getLocalId(),
                        drugId = drugId,
                        countType = CountType.REGULAR,
                        status = CountStatus.COMPLETED,
                        expiry = active.expiry.ifBlank { null },
                        lotNo = active.batchNo.ifBlank { null },
                        bottleQty = active.bottles,
                        batchId = batchId,
                        bucketId = _bucketId.value,
                    )
                )
            }
        } catch (e: Exception) {
            logger.e("persistActive failed", e)
            _errorMessage.value = LocalizedError(R.string.batch_stock_count_save_failed)
        }
    }

    fun onAdd() {
        val active = _activeNdc.value ?: return
        viewModelScope.launch {
            try {
                persistActive(active)
            } finally {
                _activeNdc.value = null
                _scannerPaused.value = false
            }
        }
    }

    /* ─────────────────────────  End count  ───────────────────────── */

    fun requestEndCount() {
        _showEndCountDialog.value = true
    }

    fun dismissEndCount() {
        _showEndCountDialog.value = false
    }

    fun confirmEndCount() {
        viewModelScope.launch {
            try {
                val batchId = _resolvedBatchId.value
                if (batchId != 0L) {
                    batchDao.markAsCompleted(batchId)
                }
            } catch (e: Exception) {
                logger.e("confirmEndCount failed", e)
            } finally {
                _showEndCountDialog.value = false
                _batchEnded.value = true
            }
        }
    }
}

/**
 * Localized one-shot error event. The VM emits a string-resource ID plus any
 * positional args; the screen resolves it via `stringResource`. Keeps the VM
 * free of Android `Context` references.
 */
data class LocalizedError(
    @androidx.annotation.StringRes val messageResId: Int,
    val formatArg: Any? = null,
)

/* ─────────────────────────  Helpers  ───────────────────────── */

/**
 * Group sealed transactions by drugId and convert to [RecentBatchRow]s, newest
 * first. "Pills" = bottleQty × packageQty + looseQty so the figure reads as
 * total pills across all bottles in this batch for that drug.
 */
private fun List<BatchTxnDto>.toRecentRows(): List<RecentBatchRow> {
    if (isEmpty()) return emptyList()
    return groupBy { it.drugId }
        .map { (_, txns) ->
            val first = txns.first()
            val totalBottles = txns.sumOf { it.bottleQty ?: 0 }
            val packageQty = txns.firstOrNull { it.packageQty != null }?.packageQty ?: 0
            val totalLoose = txns.sumOf { it.looseQty ?: 0 }
            val totalPills = totalBottles * packageQty + totalLoose
            RecentBatchRow(
                ndc = first.ndc.orEmpty(),
                drugName = first.drugName.orEmpty(),
                pills = totalPills,
                bottles = totalBottles,
            )
        }
}

