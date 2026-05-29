package com.rite.pillcounting.feature.pillCountScan.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rite.pillcounting.R
import com.rite.pillcounting.core.room.dao.BatchDao
import com.rite.pillcounting.core.room.dao.DrugMasterDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.models.BatchEntity
import com.rite.pillcounting.core.room.models.DrugMasterEntity
import com.rite.pillcounting.core.room.models.PillCountTxnEntity
import com.rite.pillcounting.feature.dispenseFlow.domain.data.IDrugRepository
import com.rite.pillcounting.feature.dispenseFlow.domain.model.GetNdcRequestModel
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
import kotlinx.coroutines.flow.updateAndGet
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
    private val drugRepository: IDrugRepository,
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
     * Timestamp of the last increment for the currently-active NDC. Used to
     * reject same-NDC rescans that arrive faster than [SAME_NDC_COOLDOWN_MS] —
     * camera jitter (label drifting in/out of focus, lot-code 2D flickering)
     * can otherwise trigger many +1 events for one physical bottle. Reset
     * whenever the active NDC changes or is cleared.
     */
    private var lastSameNdcIncrementAtMs: Long = 0L

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
        // Hide the active NDC's row from the list — it's already shown in the
        // bottom card, so duplicating it just clutters the recent list and
        // invites a confusing tap-to-reactivate on the row that already is
        // active. Totals still count every txn including the active one.
        val visibleRows = if (active != null) rows.filterNot { it.ndc == active.ndc } else rows
        BatchStockCountUiState(
            recentCounts = visibleRows,
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
        logger.d("INV_SCAN onBarcodeDetected raw='$rawValue' currentActive=${_activeNdc.value?.ndc}")
        _scannerPaused.value = true
        viewModelScope.launch {
            try {
                val isGs1 = barcodeDecoder.isGs1Barcode(rawValue)
                val decoded = if (isGs1) barcodeDecoder.decode(rawValue) else null
                val extractedGtin = if (isGs1) decoded?.gtin else barcodeDecoder.toGtin14(rawValue)
                val gtin14 = extractedGtin?.let { barcodeDecoder.toGtin14(it) }
                logger.d("INV_SCAN decoded isGs1=$isGs1 extractedGtin=$extractedGtin gtin14=$gtin14")
                if (gtin14.isNullOrBlank() || gtin14.length != 14 || !gtin14.all { it.isDigit() }) {
                    logger.w("INV_SCAN invalid label: gtin14=$gtin14")
                    _errorMessage.value = LocalizedError(R.string.batch_stock_count_invalid_label)
                    _scannerPaused.value = false
                    return@launch
                }

                val localDrug = drugMasterDao.getDrugByGtin(gtin14) ?: drugMasterDao.getDrugByNdc(gtin14)
                logger.d("INV_SCAN local lookup gtin14=$gtin14 → drug=${localDrug?.ndc} (${localDrug?.drugName}) hazardous=${localDrug?.isHazardous}")

                // Fall back to the server when the drug isn't cached locally.
                // Matches the dispense flow's behavior — unknown drugs are
                // fetched on-demand and upserted into drug_master for future
                // offline lookups.
                val drug = localDrug ?: run {
                    val drugInfo = try {
                        drugRepository.getDrugInfoByNdc(
                            GetNdcRequestModel(target_ndc = "", scanned_ndc = gtin14)
                        )
                    } catch (e: Exception) {
                        logger.e("server drug lookup failed for gtin14=$gtin14", e)
                        null
                    }
                    logger.d("INV_SCAN server lookup gtin14=$gtin14 → drugInfo=${drugInfo?.ndc} (${drugInfo?.genericName}) hazardous=${drugInfo?.isHazardous}")
                    if (drugInfo == null) {
                        _errorMessage.value = LocalizedError(R.string.batch_stock_count_drug_not_found, gtin14)
                        _scannerPaused.value = false
                        return@launch
                    }
                    val displayName = drugInfo.genericName?.takeIf { it.isNotBlank() } ?: "Unknown Drug"
                    drugMasterDao.upsertPreservingId(
                        DrugMasterEntity(
                            ndc = drugInfo.ndc,
                            drugName = displayName,
                            drugType = drugInfo.drugType,
                            gtin = gtin14,
                            packageQty = drugInfo.qty,
                            isHazardous = drugInfo.isHazardous ?: false,
                        )
                    )
                    // Re-read so we get the row with its assigned drugId.
                    drugMasterDao.getDrugByNdc(drugInfo.ndc) ?: drugMasterDao.getDrugByGtin(gtin14)
                }
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
                logger.d("INV_SCAN pre-switch currentActive=${currentActive?.ndc} newDrug=${drug.ndc} sameNdc=${currentActive?.ndc == drug.ndc}")
                if (currentActive != null && currentActive.ndc != drug.ndc) {
                    logger.d("INV_SCAN auto-committing previous active ndc=${currentActive.ndc} bottles=${currentActive.bottles}")
                    persistActive(currentActive)
                }

                val lotNo = decoded?.lotNumber
                val expiry = decoded?.expirationDate?.format(DateTimeFormatter.ofPattern("MM-dd-yyyy"))
                val packageQty = drug.packageQty ?: 0
                logger.d("INV_SCAN parsed lotNo=$lotNo expiry=$expiry packageQty=$packageQty")

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

                logger.d("INV_SCAN existing-txn lookup batchId=$batchId drugId=${drug.drugId} lot=$lotNo expiry=$expiry → existing=${existing?.localId} prevBottles=${existing?.bottleQty}")
                // Same-NDC rescan = "+1 bottle". If the card already shows this
                // NDC, bump its bottle count. Otherwise this is a fresh scan
                // (or a switch back to an NDC that was previously committed): seed
                // from the existing committed bottleQty, defaulting to 1.
                val sameAsActive = currentActive != null && currentActive.ndc == drug.ndc
                if (sameAsActive) {
                    val now = System.currentTimeMillis()
                    val sinceLast = now - lastSameNdcIncrementAtMs
                    if (sinceLast < SAME_NDC_COOLDOWN_MS) {
                        logger.d("INV_SCAN same-NDC rescan IGNORED (cooldown sinceLast=${sinceLast}ms)")
                        _scannerPaused.value = false
                        return@launch
                    }
                }
                val startBottles = when {
                    sameAsActive -> currentActive!!.bottles + 1
                    existing != null -> (existing.bottleQty ?: 0).coerceAtLeast(1) + 1
                    else -> 1
                }
                logger.d("INV_SCAN startBottles=$startBottles sameAsActive=$sameAsActive")
                val newActive = ActiveNdc(
                    ndc = drug.ndc,
                    drugName = drug.drugName ?: "",
                    bucket = _bucketId.value.orEmpty(),
                    batchNo = lotNo.orEmpty(),
                    expiry = expiry.orEmpty(),
                    pillsPerBottle = packageQty,
                    bottles = startBottles,
                    isHazardous = drug.isHazardous,
                )
                _activeNdc.value = newActive
                lastSameNdcIncrementAtMs = System.currentTimeMillis()
                logger.d("INV_SCAN activeNdc SET ndc=${drug.ndc} drug=${drug.drugName} bottles=$startBottles hazardous=${drug.isHazardous} batchId=${_resolvedBatchId.value}")

                // Persist immediately so the batch is durable from the first scan —
                // BACK/app-kill before ADD must not drop the count. persistActive
                // INSERTs on the first scan of an (ndc, lot, expiry) tuple in this
                // batch, UPDATEs on subsequent rescans (same-NDC +1 path).
                persistActive(newActive)
            } catch (e: Exception) {
                logger.e("INV_SCAN onBarcodeDetected failed", e)
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
        val updated = _activeNdc.updateAndGet { it?.copy(bottles = it.bottles + 1) } ?: return
        viewModelScope.launch { persistActive(updated) }
    }

    fun decrement() {
        val updated = _activeNdc.updateAndGet { it?.copy(bottles = (it.bottles - 1).coerceAtLeast(1)) } ?: return
        viewModelScope.launch { persistActive(updated) }
    }

    /* ─────────────────────────  Recent-row tap  ───────────────────────── */

    /**
     * User tapped a row in the Recent Counts list — re-activate that NDC.
     * Auto-commits any currently-active NDC (same path the scanner uses when
     * switching), then seeds the active card from the latest persisted txn for
     * that NDC so +/- continues from the saved bottle count.
     */
    fun onRecentRowTapped(row: RecentBatchRow) {
        val batchId = _resolvedBatchId.value
        logger.d("INV_SCAN onRecentRowTapped ndc=${row.ndc} batchId=$batchId")
        if (batchId == 0L) return
        viewModelScope.launch {
            try {
                val current = _activeNdc.value
                if (current != null && current.ndc != row.ndc) {
                    persistActive(current)
                }
                val txn = pillCountTxnDao.findLatestTxnByNdcInBatch(batchId, row.ndc)
                val drug = drugMasterDao.getDrugByNdc(row.ndc)
                if (txn == null || drug == null) {
                    logger.w("INV_SCAN onRecentRowTapped: missing txn=${txn?.localId} drug=${drug?.ndc}")
                    return@launch
                }
                _activeNdc.value = ActiveNdc(
                    ndc = drug.ndc,
                    drugName = drug.drugName ?: "",
                    bucket = _bucketId.value.orEmpty(),
                    batchNo = txn.lotNo.orEmpty(),
                    expiry = txn.expiry.orEmpty(),
                    pillsPerBottle = drug.packageQty ?: 0,
                    bottles = (txn.bottleQty ?: 0).coerceAtLeast(1),
                    isHazardous = drug.isHazardous,
                )
                lastSameNdcIncrementAtMs = System.currentTimeMillis()
            } catch (e: Exception) {
                logger.e("INV_SCAN onRecentRowTapped failed", e)
            }
        }
    }

    /* ─────────────────────────  Clear / Add  ───────────────────────── */

    fun onClear() {
        logger.d("INV_SCAN onClear (active=${_activeNdc.value?.ndc})")
        _activeNdc.value = null
        lastSameNdcIncrementAtMs = 0L
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
        logger.d("INV_SCAN persistActive START ndc=${active.ndc} bottles=${active.bottles} batchId=$batchId lot=${active.batchNo} expiry=${active.expiry}")
        if (batchId == 0L) {
            logger.w("INV_SCAN persistActive ABORT: no batchId")
            _errorMessage.value = LocalizedError(R.string.batch_stock_count_no_active_batch)
            return
        }
        try {
            val drugId = drugMasterDao.getDrugIdByNdc(active.ndc)
            logger.d("INV_SCAN persistActive drugId lookup ndc=${active.ndc} → drugId=$drugId")
            if (drugId == null) {
                logger.w("INV_SCAN persistActive ABORT: drugId null for ndc=${active.ndc}")
                _errorMessage.value = LocalizedError(R.string.batch_stock_count_drug_not_found, active.ndc)
                return
            }
            val existing = pillCountTxnDao.findSealedTxnInBatch(
                batchId = batchId,
                drugId = drugId.toString(),
                lotNo = active.batchNo.ifBlank { null },
                expiry = active.expiry.ifBlank { null },
            )
            logger.d("INV_SCAN persistActive existing-txn lookup → existing=${existing?.localId} prevBottles=${existing?.bottleQty}")
            if (existing != null) {
                pillCountTxnDao.update(
                    existing.copy(
                        bottleQty = active.bottles,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                logger.d("INV_SCAN persistActive UPDATED txn localId=${existing.localId} drugId=$drugId bottles=${active.bottles}")
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
                logger.d("INV_SCAN persistActive INSERTED new txn drugId=$drugId bottles=${active.bottles}")
            }
        } catch (e: Exception) {
            logger.e("INV_SCAN persistActive FAILED", e)
            _errorMessage.value = LocalizedError(R.string.batch_stock_count_save_failed)
        }
    }

    /**
     * ADD is now purely a visual transition: txns are written on every scan
     * and on every +/- tap (see [persistActive]). ADD just closes the active
     * card so the panel falls back to the Summary view. We still call
     * persistActive as a belt-and-braces flush in case a +/- tap landed
     * milliseconds before ADD and its coroutine hasn't completed.
     */
    fun onAdd() {
        val active = _activeNdc.value
        logger.d("INV_SCAN onAdd active=${active?.ndc} bottles=${active?.bottles}")
        if (active == null) {
            logger.w("INV_SCAN onAdd ABORT: no active")
            return
        }
        viewModelScope.launch {
            try {
                persistActive(active)
            } finally {
                _activeNdc.value = null
                _scannerPaused.value = false
                logger.d("INV_SCAN onAdd DONE — activeNdc cleared")
            }
        }
    }

    /* ─────────────────────────  Scan pills hand-off  ───────────────────────── */

    /**
     * SCAN PILLS hand-off (Path 1): the user has an active scanned NDC and wants
     * to count loose/open pills for it. We find-or-create a REGULAR transaction
     * for that drug in the current batch, mark it PARTIAL (loose-counting in
     * progress), persist its id via [PreferenceHelper.saveTxnId] so the legacy
     * pill-count screen picks it up, and invoke [onReady] with (batchId) on the
     * caller so it can navigate.
     *
     * The legacy flow then counts loose pills into this same txn (it calls
     * `incrementLooseQty` on every ADD) and marks it COMPLETED on DONE — the
     * counted pills surface on this NDC's Recent Counts row as loose pills on
     * top of any sealed bottles (toRecentRows sums bottleQty*packageQty + looseQty).
     *
     * Works with or without an active NDC. The legacy pill-count flow scans its
     * own NDC, so SCAN PILLS is always available: with no active NDC we simply
     * navigate into the batch (creating it if needed) and let that flow establish
     * its own txn. With an active NDC we additionally stage that NDC's txn so the
     * counted loose pills accumulate onto its Recent Counts row.
     */
    fun onScanPillsForActive(onReady: (batchId: Long) -> Unit) {
        val active = _activeNdc.value
        viewModelScope.launch {
            try {
                // Flush the active bottle count first (if any) so it isn't lost
                // while the user is away counting pills.
                if (active != null) persistActive(active)

                val batchId = _resolvedBatchId.value.takeIf { it != 0L } ?: ensureBatchCreated()
                if (batchId == 0L) {
                    _errorMessage.value = LocalizedError(R.string.batch_stock_count_no_active_batch)
                    return@launch
                }

                // No active NDC: nothing to stage — the pill-count flow scans its
                // own NDC and creates its own txn. Clear any stale staged txn id
                // (the legacy flow treats 0 as "start fresh") and hand off.
                if (active == null) {
                    preferenceHelper.saveTxnId(0)
                    logger.d("INV_SCAN onScanPillsForActive (no active NDC) → batchId=$batchId, cleared staged txn")
                    onReady(batchId)
                    return@launch
                }

                val drugId = drugMasterDao.getDrugIdByNdc(active.ndc)
                if (drugId == null) {
                    _errorMessage.value = LocalizedError(R.string.batch_stock_count_drug_not_found, active.ndc)
                    return@launch
                }
                // Reuse the existing txn for this (drug, lot, expiry) so loose
                // pills accumulate onto the same row; otherwise create one.
                val existing = pillCountTxnDao.findSealedTxnInBatch(
                    batchId = batchId,
                    drugId = drugId.toString(),
                    lotNo = active.batchNo.ifBlank { null },
                    expiry = active.expiry.ifBlank { null },
                )
                val txnId = existing?.txnId ?: pillCountTxnDao.upsertPreservingId(
                    PillCountTxnEntity(
                        localId = preferenceHelper.getLocalId(),
                        drugId = drugId,
                        countType = CountType.REGULAR,
                        status = CountStatus.PARTIAL,
                        expiry = active.expiry.ifBlank { null },
                        lotNo = active.batchNo.ifBlank { null },
                        bottleQty = active.bottles,
                        batchId = batchId,
                        bucketId = _bucketId.value,
                    )
                )
                preferenceHelper.saveTxnId(txnId)
                logger.d("INV_SCAN onScanPillsForActive ndc=${active.ndc} → txnId=$txnId batchId=$batchId")
                onReady(batchId)
            } catch (e: Exception) {
                logger.e("INV_SCAN onScanPillsForActive failed", e)
                _errorMessage.value = LocalizedError(R.string.batch_stock_count_scan_failed)
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

/**
 * Minimum interval between two same-NDC rescans counting as a real "+1 bottle"
 * event. Anything faster is treated as the camera re-firing on a label still in
 * view (jitter, lot-code flicker, autofocus bounce) and ignored. 1500ms reflects
 * the human pace of swapping a bottle aside and bringing the next under the
 * camera; tighten if power users complain it's sluggish.
 */
private const val SAME_NDC_COOLDOWN_MS = 1500L

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

