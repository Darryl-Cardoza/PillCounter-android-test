package com.dispensesure.retail.feature.stockCount.sealed.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dispensesure.retail.R
import com.dispensesure.retail.core.audit.AuditLogger
import com.dispensesure.retail.core.audit.model.BatchCompleted
import com.dispensesure.retail.core.audit.model.BatchCreated
import com.dispensesure.retail.core.audit.model.BatchSource
import com.dispensesure.retail.core.audit.model.BottleCountAdjusted
import com.dispensesure.retail.core.audit.model.BottleCounted
import com.dispensesure.retail.core.audit.model.BottleLineDeleted
import com.dispensesure.retail.core.audit.model.LineDeleted
import com.dispensesure.retail.core.audit.model.LineEdited
import com.dispensesure.retail.core.faceAuth.data.OperatorNameProvider
import com.dispensesure.retail.core.region.RegionProvider
import com.dispensesure.retail.core.room.dao.DrugMasterDao
import com.dispensesure.retail.core.room.dao.StockBatchDao
import com.dispensesure.retail.core.room.dao.StockBottleInfoDao
import com.dispensesure.retail.core.room.dao.StockTxnDao
import com.dispensesure.retail.core.room.dao.insertNewInProgressBatch
import com.dispensesure.retail.core.room.models.StockBottleInfoEntity
import com.dispensesure.retail.core.room.models.StockTxnEntity
import com.dispensesure.retail.core.room.models.dtos.RequestedDrugDto
import com.dispensesure.retail.core.room.models.dtos.StockBottleInfoDto
import com.dispensesure.retail.core.room.models.enums.CountStatus
import com.dispensesure.retail.core.scanning.data.DrugResolver
import com.dispensesure.retail.core.scanning.domain.model.DrugLookup
import com.dispensesure.retail.core.scanning.logic.ScanDecoder
import com.dispensesure.retail.core.tourguide.FtueController
import com.dispensesure.retail.core.tourguide.TutorialData
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.logger.LogEvent
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import com.dispensesure.retail.feature.hl7.core.Hl7EventHandler
import com.dispensesure.retail.feature.hl7.data.repository.StockHl7Repository
import com.dispensesure.retail.feature.stockCount.sealed.domain.model.ActiveNdc
import com.dispensesure.retail.feature.stockCount.sealed.domain.model.EditBatchRow
import com.dispensesure.retail.feature.stockCount.sealed.domain.model.EditDrugDetails
import com.dispensesure.retail.feature.stockCount.sealed.domain.model.RecentBatchRow
import com.dispensesure.retail.feature.stockCount.sealed.domain.model.StockCountUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the new tablet-landscape Inventory scan screen.
 *
 * Lifecycle:
 *  - On init: resolves [batchId] from SavedStateHandle and reads the parent
 *    StockBatchEntity once (for bucket display on the active card).
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
class StockCountViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val stockBatchDao: StockBatchDao,
    private val stockTxnDao: StockTxnDao,
    private val stockBottleInfoDao: StockBottleInfoDao,
    private val drugMasterDao: DrugMasterDao,
    private val preferenceHelper: PreferenceHelper,
    private val operatorNameProvider: OperatorNameProvider,
    private val scanDecoder: ScanDecoder,
    private val drugResolver: DrugResolver,
    private val stockHl7Repository: StockHl7Repository,
    private val hl7EventHandler: Hl7EventHandler,
    private val regionProvider: RegionProvider,
    private val auditLogger: AuditLogger,
) : ViewModel() {

    private val logger = AppLogger("StockCountVM")

    /**
     * Route arg. 0 when the caller hasn't created a batch yet (Inventory quick
     * action from the dashboard) — the batch is created lazily on the first
     * successful NDC scan. Non-zero when resuming an existing batch.
     */
    private val argBatchId: Long = savedStateHandle["batch_id"] ?: 0L

    /** Route arg used only when [argBatchId] is 0 — seeds the lazily-created batch. */
    private val argBucketId: String? = savedStateHandle.get<String>("bucket_id")?.ifBlank { null }

    private val resolvedBatchId = MutableStateFlow(argBatchId)

    /** Bucket label rendered on the active card. Empty until the batch loads. */
    private val _bucketId = MutableStateFlow<String?>(null)

    /**
     * Expected NDCs for a PMS-requested (INR^U04) stock count. A PMS batch is
     * pre-populated with one txn per requested drug (see
     * StockHl7Repository.handleInrInventoryRequest), so the set of NDCs already in the
     * batch IS the request's expected list. Non-null only when this batch is
     * PMS-sourced (StockBatchEntity.requestIdFromPMS != null); null means "no
     * restriction" (manually started inventory accepts any valid NDC). Loaded
     * once in init and used by [onBarcodeDetected] to reject off-list scans.
     */
    private var expectedNdcs: Set<String>? = null

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
     * Debounce for the +/- counter. Each tick updates the in-memory count
     * instantly (UI stays live) but the DB write is deferred — we cancel any
     * pending persist and reschedule, so a long-press that fires ~12 ticks/sec
     * results in ONE DB upsert shortly after the user lets go, instead of dozens.
     */
    private var counterPersistJob: Job? = null

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

    /**
     * Backing state for the "Edit Details" panel. Non-null while the panel is
     * open; holds the active drug's sealed-bottle and open-pill rows aggregated
     * from its batch transactions. The panel keeps a local working copy for the
     * +/- edits and removals; [saveEditDetails] commits the final lists.
     */
    private val _editDetails = MutableStateFlow<EditDrugDetails?>(null)
    val editDetails: StateFlow<EditDrugDetails?> = _editDetails.asStateFlow()

    /** Emit true once the batch has been marked completed; the screen pops back. */
    private val _batchEnded = MutableStateFlow(false)
    val batchEnded: StateFlow<Boolean> = _batchEnded.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val recentRows: StateFlow<List<RecentBatchRow>> = resolvedBatchId
        .flatMapLatest { id ->
            if (id == 0L) flowOf(emptyList())
            // Merge the counted bottle lines with the batch's requested-drug headers so a
            // PMS batch shows every requested drug in Recent Counts by default (zero count)
            // before anything is scanned. Counted drugs render from their bottle-line totals;
            // still-uncounted requested drugs render as zero placeholders. Manual batches only
            // ever have a header once a bottle line exists, so they're unaffected.
            else combine(
                stockBottleInfoDao.observeByBatchId(id),
                stockTxnDao.observeRequestedDrugs(id),
            ) { lines, requested -> mergeRecentRows(lines.toRecentRows(), requested) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val uiState: StateFlow<StockCountUiState> = combine(
        recentRows,
        _activeNdc,
    ) { rows, active ->
        // Hide the active NDC's row from the list — it's already shown in the
        // bottom card, so duplicating it just clutters the recent list and
        // invites a confusing tap-to-reactivate on the row that already is
        // active. Totals still count every txn including the active one.
        val visibleRows = if (active != null) rows.filterNot { it.ndc == active.ndc } else rows
        StockCountUiState(
            recentCounts = visibleRows,
            activeNdc = active,
            totalNdcs = rows.size,
            totalPills = rows.sumOf { it.pills },
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000L),
        StockCountUiState(emptyList(), null, 0, 0),
    )

    init {
        viewModelScope.launch {
            try {
                if (argBatchId != 0L) {
                    resolvedBatchId.value = argBatchId
                    val batch = stockBatchDao.getById(argBatchId)
                    _bucketId.value = batch?.bucketId
                    // PMS-requested inventory: lock scanning to the requested NDCs.
                    // The batch was pre-populated with a txn per requested drug, so
                    // those NDCs are the allowed set. Manually started batches
                    // (requestIdFromPMS == null) stay unrestricted.
                    if (!batch?.requestIdFromPMS.isNullOrBlank()) {
                        // Source the allowlist from the stock-txn headers, NOT stock_bottle_info: a
                        // freshly received PMS batch has one header per requested drug but no
                        // bottle lines yet, so reading stock_bottle_info would yield an empty set and
                        // reject every scan — including the requested NDCs.
                        expectedNdcs = stockTxnDao.getNdcsForBatch(argBatchId)
                            .filter { it.isNotBlank() }
                            .toSet()
                        logger.i("INV_SCAN PMS batch=$argBatchId expectedNdcs=$expectedNdcs")
                    }
                } else {
                    // No batch yet — the user selected a bucket on the dashboard but the
                    // batch row will be created on the first successful NDC scan.
                    _bucketId.value = argBucketId
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("INV_SCAN init failed for batchId=$argBatchId", e, event = LogEvent.INVENTORY_SCAN_FAILED)
                _errorMessage.value = LocalizedError(R.string.batch_stock_count_scan_failed)
            }
        }
    }

    /**
     * Inserts a new StockBatchEntity for the current bucket and stores its id. Called
     * once, on the first successful NDC scan, when the screen was entered via the
     * Inventory quick action (which intentionally defers batch creation so an
     * abandoned session never produces an empty batch row).
     *
     * Returns the new batchId on success, or 0 if the insert failed.
     */
    private suspend fun ensureBatchCreated(): Long {
        val existing = resolvedBatchId.value
        if (existing != 0L) return existing
        return try {
            val bucket = if (FtueController.state.isActive) TutorialData.BUCKET else _bucketId.value
            val newId = stockBatchDao.insertNewInProgressBatch(bucketId = bucket, region = regionProvider.current)
            resolvedBatchId.value = newId
            auditLogger.logOutsideTour(BatchCreated(batchId = newId, source = BatchSource.MANUAL, bucket = bucket, requestedNdcs = emptyList()))
            newId
        } catch (e: Exception) {
            logger.e("ensureBatchCreated failed", e, event = LogEvent.BATCH_CREATE_FAILED)
            0L
        }
    }

    /**
     * Adopts the batchId the loose count (SCAN PILLS) saved into. Called by
     * [com.dispensesure.retail.feature.stockCount.sealed.presentation.shell.StockCountHost] when
     * [com.dispensesure.retail.navigation.Screen.LooseCount.RESULT_BATCH_ID] lands on this entry's SavedStateHandle.
     *
     * Only takes effect when this VM has no batch bound yet
     * ([resolvedBatchId] == 0L). Setting [resolvedBatchId] re-triggers the
     * `flatMapLatest` in [recentRows], which subscribes to
     * [stockBottleInfoDao.observeByBatchId] + [stockTxnDao.observeRequestedDrugs]
     * against the real batch so the just-counted bottle appears in the list.
     * Also hydrates (for PMS batches) [expectedNdcs] the same
     * way the `init` block does.
     *
     * @param batchId The batchId the loose count saved into. Ignored when 0.
     */
    fun adoptBatchId(batchId: Long) {
        if (batchId == 0L) return
        if (resolvedBatchId.value != 0L) return
        viewModelScope.launch {
            try {
                resolvedBatchId.value = batchId
                val batch = stockBatchDao.getById(batchId)
                if (!batch?.requestIdFromPMS.isNullOrBlank()) {
                    expectedNdcs = stockTxnDao.getNdcsForBatch(batchId)
                        .filter { it.isNotBlank() }
                        .toSet()
                }
                logger.i("INV_SCAN adopted lazily-created batchId=$batchId bucketId=${batch?.bucketId}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("INV_SCAN adoptBatchId failed for batchId=$batchId", e, event = LogEvent.INVENTORY_SCAN_FAILED)
            }
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
    /**
     * Entry point for a Bluetooth HID barcode scanner. Same as [onBarcodeDetected]
     * but skips the same-NDC cooldown — each BT trigger is a deliberate user action,
     * unlike camera frames where the same barcode can appear dozens of times per second.
     */
    fun onBtBarcodeDetected(rawValue: String) = onBarcodeDetected(rawValue, bypassCooldown = true)

    fun onBarcodeDetected(rawValue: String) = onBarcodeDetected(rawValue, bypassCooldown = false)

    private fun onBarcodeDetected(rawValue: String, bypassCooldown: Boolean) {
        // No real scans during the tour; the demo data comes from the tour card's Next.
        if (FtueController.state.isActive) return
        logger.d("INV_SCAN onBarcodeDetected raw='$rawValue' bypassCooldown=$bypassCooldown currentActive=${_activeNdc.value?.ndc}")
        _scannerPaused.value = true
        viewModelScope.launch {
            try {
                logger.i(
                    "INV_SCAN RAW rawValue='$rawValue' | " +
                            "length=${rawValue.length} | " +
                            "bypassCooldown=$bypassCooldown"
                )
                val scan = scanDecoder.decode(rawValue, regionProvider.current)
                val gtin14 = scan.gtin14
                logger.i(
                    "INV_SCAN DECODED isGs1=${scan.isGs1} | " +
                            "gtin14=$gtin14 | " +
                            "lookupCode=${scan.lookupCode} | " +
                            "lot=${scan.lot} | " +
                            "expiry=${scan.expiry} | " +
                            "serial=${scan.serial}"
                )

                // Determine the lookup key for the drug database.
                // Primary path: the decoder's lookup code (GTIN-14, or for Canada a DIN as scanned).
                // Fallback: strip non-digits from the raw value — BT-scanner QR
                // codes often encode a plain NDC (10-11 digits) that toGtin14
                // rejects, but the drug_master table can resolve it via getDrugByNdc.
                val scanKey: String
                val decodedLookupCode = scan.lookupCode
                if (!decodedLookupCode.isNullOrBlank()) {
                    scanKey = decodedLookupCode
                } else {
                    val rawDigits = rawValue.filter { it.isDigit() }
                    if (rawDigits.length in 10..14) {
                        scanKey = rawDigits
                        logger.i("INV_SCAN GTIN-14 extraction failed, falling back to raw NDC: $scanKey")
                    } else {
                        logger.w("INV_SCAN invalid label: gtin14=$gtin14 rawValue=$rawValue")
                        _errorMessage.value = LocalizedError(R.string.batch_stock_count_invalid_label)
                        _scannerPaused.value = false
                        return@launch
                    }
                }

                val drug = when (val lookup = drugResolver.resolve(
                    code = scanKey,
                    allowNoName = true,
                    scannedGtin = gtin14,
                )) {
                    is DrugLookup.Found -> lookup.drug
                    else -> {
                        logger.w("INV_SCAN drug not resolved for scanKey=$scanKey → $lookup")
                        _errorMessage.value = LocalizedError(R.string.batch_stock_count_drug_not_found, scanKey)
                        _scannerPaused.value = false
                        return@launch
                    }
                }
                logger.d("INV_SCAN drug scanKey=$scanKey → ${drug.ndc} (${drug.drugName}) hazardous=${drug.isHazardous}")

                // PMS request validation: when this batch came from an INR^U04
                // request it carries a fixed set of expected NDCs. Reject a scan
                // for any drug outside that set so the user can't count an NDC the
                // PMS never asked for. Manually started batches have
                // expectedNdcs == null and accept any valid NDC.
                val allowed = expectedNdcs
                if (allowed != null && drug.ndc !in allowed) {
                    logger.w("INV_SCAN ndc=${drug.ndc} not in PMS request $allowed — rejecting scan")
                    _errorMessage.value =
                        LocalizedError(R.string.batch_stock_count_ndc_not_in_request, drug.ndc)
                    _scannerPaused.value = false
                    return@launch
                }

                // First valid scan in a fresh stock-count session: create the
                // StockBatchEntity now so abandoned sessions leave no DB row.
                if (resolvedBatchId.value == 0L) {
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

                val lotNo = scan.lot
                val serialNo = scan.serial
                val expiry = scan.expiry
                val packageQty = drug.packageQty ?: 0
                logger.d("INV_SCAN parsed lotNo=$lotNo serialNo=$serialNo expiry=$expiry packageQty=$packageQty")

                // Look for an existing sealed txn for this (drug, lot, expiry)
                // in the current batch.
                val batchId = resolvedBatchId.value
                val existingStockTxn = if (batchId != 0L) {
                    stockTxnDao.findByDrugInBatch(batchId, drug.drugId)
                } else null
                val existing = existingStockTxn?.let {
                    stockBottleInfoDao.findSealedLine(it.txnId, lotNo, expiry)
                }

                logger.d("INV_SCAN existing-line lookup batchId=$batchId drugId=${drug.drugId} lot=$lotNo expiry=$expiry → existing=${existing?.bottleId} prevBottles=${existing?.bottleQty}")
                // Same-NDC rescan = "+1 bottle". If the card already shows this
                // NDC, bump its bottle count. Otherwise this is a fresh scan
                // (or a switch back to an NDC that was previously committed): seed
                // from the existing committed bottleQty, defaulting to 1.
                val sameAsActive = currentActive != null && currentActive.ndc == drug.ndc
                if (sameAsActive && !bypassCooldown) {
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
                    existing != null -> (existing.bottleQty ?: 0) + 1
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
                    openPills = openPillsTotal(batchId, drug.ndc),
                    isHazardous = drug.isHazardous,
                    serialNo = serialNo,
                )
                _activeNdc.value = newActive
                lastSameNdcIncrementAtMs = System.currentTimeMillis()
                logger.d("INV_SCAN activeNdc SET ndc=${drug.ndc} drug=${drug.drugName} bottles=$startBottles hazardous=${drug.isHazardous} batchId=${resolvedBatchId.value}")

                // Persist immediately so the batch is durable from the first scan —
                // BACK/app-kill before ADD must not drop the count. persistActive
                // INSERTs on the first scan of an (ndc, lot, expiry) tuple in this
                // batch, UPDATEs on subsequent rescans (same-NDC +1 path).
                persistActive(newActive, isScan = true)
            } catch (e: Exception) {
                logger.e("INV_SCAN onBarcodeDetected failed", e, event = LogEvent.INVENTORY_SCAN_FAILED)
                _errorMessage.value = LocalizedError(R.string.batch_stock_count_scan_failed)
                _scannerPaused.value = false
            }
        }
    }

    /**
     * Tour-only escape hatch for the scan-NDC step's dummy "Next" button: when
     * there's no real bottle to scan, seeds/reuses the same fixed demo drug the
     * dispense flow's dummy fallback uses and activates it exactly as a real scan
     * would — skipping barcode decode entirely since there's no real label to decode.
     */
    fun useDummyNdcData(demoDrugName: String) {
        if (!FtueController.state.isActive) return
        FtueController.track(viewModelScope.launch {
            try {
                TutorialData.upsertDemoDrug(drugMasterDao, demoDrugName, regionProvider.current)
                val drug = drugMasterDao.getDrugByNdc(TutorialData.DEMO_NDC, regionProvider.current) ?: return@launch

                if (resolvedBatchId.value == 0L && ensureBatchCreated() == 0L) return@launch

                val currentActive = _activeNdc.value
                if (currentActive != null && currentActive.ndc != drug.ndc) {
                    persistActive(currentActive)
                }
                val startBottles = if (currentActive != null && currentActive.ndc == drug.ndc) {
                    currentActive.bottles + 1
                } else {
                    1
                }
                val newActive = ActiveNdc(
                    ndc = drug.ndc,
                    drugName = drug.drugName ?: "",
                    bucket = _bucketId.value.orEmpty(),
                    batchNo = "",
                    expiry = "",
                    pillsPerBottle = drug.packageQty ?: 0,
                    bottles = startBottles,
                    openPills = openPillsTotal(resolvedBatchId.value, drug.ndc),
                    isHazardous = drug.isHazardous,
                )
                _activeNdc.value = newActive
                persistActive(newActive)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("INV_SCAN useDummyNdcData failed", e)
                _errorMessage.value = LocalizedError(R.string.batch_stock_count_scan_failed)
            }
        })
    }

    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    /** True when the backend's colour-image setting is off; the barcode analyzer saves its frame grayscale then. */
    fun isGrayscaleImage(): Boolean = preferenceHelper.isGrayscaleImage()

    /* ─────────────────────────  Counter  ───────────────────────── */

    fun increment() {
        // Aggregate card (multiple sealed txns): the counter sums several
        // transactions, so +/- can't be attributed to one — edit per-row in Edit
        // Details instead. Ignore here rather than show a value that won't persist.
        if (_activeNdc.value?.aggregated == true) return
        val updated = _activeNdc.updateAndGet { it?.copy(bottles = it.bottles + 1) } ?: return
        schedulePersist(updated)
    }

    fun decrement() {
        if (_activeNdc.value?.aggregated == true) return
        val updated = _activeNdc.updateAndGet {
            if (it == null) return@updateAndGet null
            // Floor at 0 when the NDC carries open pills (a loose-only count is a
            // valid 0-bottle state); otherwise keep the 1-bottle floor (CLEAR is the
            // only way to drop a pure sealed-bottle scan).
            val floor = if (it.openPills > 0) 0 else 1
            it.copy(bottles = (it.bottles - 1).coerceAtLeast(floor))
        } ?: return
        schedulePersist(updated)
    }

    /**
     * Debounced persist for the +/- counter: cancel any pending write and
     * schedule a new one after [COUNTER_PERSIST_DEBOUNCE_MS]. Rapid long-press
     * ticks keep resetting the timer, so only the final value is written once the
     * user stops — one DB upsert per long-press instead of one per tick.
     */
    private fun schedulePersist(active: ActiveNdc) {
        counterPersistJob?.cancel()
        counterPersistJob = FtueController.track(viewModelScope.launch {
            delay(COUNTER_PERSIST_DEBOUNCE_MS)
            // Clear the handle BEFORE persisting so persistActive's own
            // counterPersistJob?.cancel() doesn't cancel this still-running job.
            counterPersistJob = null
            persistActive(active)
        })
    }

    /* ─────────────────────────  Recent-row tap  ───────────────────────── */

    /**
     * User tapped a row in the Recent Counts list — re-activate that NDC.
     * Auto-commits any currently-active NDC (same path the scanner uses when
     * switching), then seeds the active card from the drug's batch totals (sum of
     * sealed bottles + sum of open pills across all its transactions) rather than
     * just the latest transaction.
     */
    fun onRecentRowTapped(row: RecentBatchRow) {
        val batchId = resolvedBatchId.value
        logger.d("INV_SCAN onRecentRowTapped ndc=${row.ndc} batchId=$batchId")
        if (batchId == 0L) return
        viewModelScope.launch {
            try {
                val current = _activeNdc.value
                if (current != null && current.ndc != row.ndc) {
                    persistActive(current)
                }
                val active = buildActiveTotals(batchId, row.ndc)
                if (active == null) {
                    logger.w("INV_SCAN onRecentRowTapped: no drug/txns for ndc=${row.ndc}")
                    return@launch
                }
                _activeNdc.value = active
                lastSameNdcIncrementAtMs = System.currentTimeMillis()
            } catch (e: Exception) {
                logger.e("INV_SCAN onRecentRowTapped failed", e, event = LogEvent.INVENTORY_COUNT_FAILED)
            }
        }
    }

    /* ─────────────────────────  Clear / Add  ───────────────────────── */

    fun onClear() {
        logger.d("INV_SCAN onClear (active=${_activeNdc.value?.ndc})")
        // Drop any pending debounced counter write — the active NDC is going away,
        // so a late persist of the cleared count must not fire.
        counterPersistJob?.cancel()
        _activeNdc.value = null
        lastSameNdcIncrementAtMs = 0L
        _scannerPaused.value = false
    }

    /**
     * Persists the given active NDC into the current batch. Shared by ADD and
     * by the auto-commit path when a different NDC is scanned while one is
     * already active. Updates the existing sealed txn if found, otherwise
     * inserts a new one.
     *
     * @param isScan True only from a barcode scan: a changed count is BOTTLE_COUNTED, otherwise BOTTLE_COUNT_ADJUSTED.
     */
    private suspend fun persistActive(active: ActiveNdc, isScan: Boolean = false) {
        // Any explicit persist supersedes a pending debounced counter write, so
        // cancel it to avoid a redundant follow-up upsert of the same row.
        counterPersistJob?.cancel()
        // Aggregate card: [bottles] is a sum across multiple sealed transactions, so
        // it can't be written back onto a single one without corrupting the others.
        // Per-transaction edits go through the Edit Details panel (saveEditDetails).
        if (active.aggregated) {
            logger.d("INV_SCAN persistActive SKIP: aggregated card (multiple sealed txns) ndc=${active.ndc}")
            return
        }
        val batchId = resolvedBatchId.value
        logger.d("INV_SCAN persistActive START ndc=${active.ndc} bottles=${active.bottles} batchId=$batchId lot=${active.batchNo} expiry=${active.expiry}")
        if (batchId == 0L) {
            logger.w("INV_SCAN persistActive ABORT: no batchId")
            _errorMessage.value = LocalizedError(R.string.batch_stock_count_no_active_batch)
            return
        }
        try {
            val drugId = drugMasterDao.getDrugIdByNdc(active.ndc, regionProvider.current)
            logger.d("INV_SCAN persistActive drugId lookup ndc=${active.ndc} → drugId=$drugId")
            if (drugId == null) {
                logger.w("INV_SCAN persistActive ABORT: drugId null for ndc=${active.ndc}")
                _errorMessage.value = LocalizedError(R.string.batch_stock_count_drug_not_found, active.ndc)
                return
            }
            val lotNo = active.batchNo.ifBlank { null }
            val expNo = active.expiry.ifBlank { null }
            val stockTxn = stockTxnDao.findByDrugInBatch(batchId, drugId)
            val existing = stockTxn?.let { stockBottleInfoDao.findSealedLine(it.txnId, lotNo, expNo) }
            logger.d("INV_SCAN persistActive existing-line lookup → existing=${existing?.bottleId} prevBottles=${existing?.bottleQty}")
            if (existing != null && active.bottles <= 0 && (existing.looseQty ?: 0) == 0) {
                // Card dropped to 0 bottles: an empty line would never be found again, so remove it.
                stockBottleInfoDao.delete(existing.bottleId)
                logger.d("INV_SCAN persistActive DELETED empty bottleId=${existing.bottleId}")
                auditLogger.logOutsideTour(
                    BottleLineDeleted(
                        batchId = batchId,
                        lineId = existing.bottleId,
                        ndc = active.ndc,
                        lot = lotNo,
                        oldBottleQty = existing.bottleQty
                    )
                )
            } else if (existing != null) {
                stockBottleInfoDao.update(
                    existing.copy(
                        bottleQty = active.bottles,
                        serialNo = active.serialNo ?: existing.serialNo,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                logger.d("INV_SCAN persistActive UPDATED bottleId=${existing.bottleId} drugId=$drugId bottles=${active.bottles}")
                // A flush with nothing new (row tap, scan pills) writes the same count and logs nothing.
                if (existing.bottleQty != active.bottles) {
                    auditLogger.logOutsideTour(
                        if (isScan) {
                            BottleCounted(
                                batchId = batchId,
                                ndc = active.ndc,
                                lot = lotNo,
                                expiry = expNo,
                                serial = active.serialNo,
                                oldBottleQty = existing.bottleQty,
                                newBottleQty = active.bottles
                            )
                        } else {
                            BottleCountAdjusted(
                                batchId = batchId,
                                ndc = active.ndc,
                                lot = lotNo,
                                oldBottleQty = existing.bottleQty,
                                newBottleQty = active.bottles
                            )
                        }
                    )
                }
            } else if (active.bottles <= 0) {
                // Open-pills-only NDC (no sealed bottle scanned): nothing to record as
                // a sealed bottle line. The loose pills live on their own line from SCAN PILLS.
                logger.d("INV_SCAN persistActive SKIP insert: bottles<=0 (open-pills-only)")
            } else {
                val stockTxnId = stockTxn?.txnId ?: stockTxnDao.upsertPreservingId(
                    StockTxnEntity(
                        drugId = drugId,
                        status = CountStatus.COMPLETED,
                        batchId = batchId,
                        bucketId = _bucketId.value,
                    )
                )
                stockBottleInfoDao.insert(
                    StockBottleInfoEntity(
                        stockTxnId = stockTxnId,
                        batchId = batchId,
                        lotNo = lotNo,
                        expNo = expNo,
                        serialNo = active.serialNo,
                        bottleQty = active.bottles,
                    )
                )
                logger.d("INV_SCAN persistActive INSERTED new bottle line stockTxnId=$stockTxnId drugId=$drugId bottles=${active.bottles}")
                // A new line is always a count, whichever path wrote it.
                auditLogger.logOutsideTour(
                    BottleCounted(
                        batchId = batchId,
                        ndc = active.ndc,
                        lot = lotNo,
                        expiry = expNo,
                        serial = active.serialNo,
                        oldBottleQty = null,
                        newBottleQty = active.bottles
                    )
                )
                // Stock txn added → keep the batch's live totals in sync.
                stockTxnDao.refreshBatchTotalNdcs(batchId)
                stockTxnDao.updateBatchUserName(batchId, operatorNameProvider().display())
            }
        } catch (e: Exception) {
            logger.e("INV_SCAN persistActive FAILED", e, event = LogEvent.INVENTORY_COUNT_FAILED)
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
        FtueController.track(viewModelScope.launch {
            try {
                persistActive(active)
            } finally {
                _activeNdc.value = null
                _scannerPaused.value = false
                logger.d("INV_SCAN onAdd DONE — activeNdc cleared")
            }
        })
    }

    /* ─────────────────────────  Edit Details panel  ───────────────────────── */

    /**
     * Opens the "Edit Details" panel for the currently-active NDC. Loads every
     * non-deleted transaction of that drug in the current batch and splits them
     * into sealed-bottle rows (those carrying a [bottleQty]) and open-pill rows
     * (those carrying a [looseQty]). Flushes the active count first so the panel
     * reflects the latest persisted values. No-op when nothing is active.
     */
    fun openEditDetails() {
        val active = _activeNdc.value ?: return
        val batchId = resolvedBatchId.value
        if (batchId == 0L) return
        viewModelScope.launch {
            try {
                // Flush the in-memory count so the loaded rows include the active edit.
                persistActive(active)
                val bottles = stockBottleInfoDao.getByBatchId(batchId)
                    .filter { it.ndc == active.ndc }
                val sealed = bottles
                    .filter { (it.bottleQty ?: 0) > 0 }
                    .map { EditBatchRow(it.bottleId, it.lotNo.orEmpty(), it.expiry.orEmpty(), it.bottleQty ?: 0) }
                val open = bottles
                    .filter { (it.looseQty ?: 0) > 0 }
                    .map { EditBatchRow(it.bottleId, it.lotNo.orEmpty(), it.expiry.orEmpty(), it.looseQty ?: 0) }
                _editDetails.value = EditDrugDetails(
                    ndc = active.ndc,
                    drugName = active.drugName,
                    bucket = active.bucket,
                    sealedBottles = sealed,
                    openPills = open,
                )
            } catch (e: Exception) {
                logger.e("INV_SCAN openEditDetails failed", e, event = LogEvent.INVENTORY_COUNT_FAILED)
                _errorMessage.value = LocalizedError(R.string.batch_stock_count_scan_failed)
            }
        }
    }

    fun dismissEditDetails() {
        _editDetails.value = null
    }

    /** Sum of loose/open pills committed for [ndc] in [batchId] (0 if none). */
    private suspend fun openPillsTotal(batchId: Long, ndc: String): Int {
        if (batchId == 0L) return 0
        return stockBottleInfoDao.getByBatchId(batchId)
            .filter { it.ndc == ndc }
            .sumOf { it.looseQty ?: 0 }
    }

    /**
     * Build the active-card model for [ndc] from ALL of its transactions in
     * [batchId], so the counter reflects the drug's batch totals — sum of sealed
     * bottles + sum of open pills — rather than just the latest transaction.
     * Lot/expiry anchor to a sealed transaction (so +/- maps onto it); when the NDC
     * has only open pills, the first transaction supplies them. Returns null when
     * the drug or its transactions are missing.
     */
    private suspend fun buildActiveTotals(batchId: Long, ndc: String): ActiveNdc? {
        if (batchId == 0L) return null
        val drug = drugMasterDao.getDrugByNdc(ndc, regionProvider.current) ?: return null
        val bottles = stockBottleInfoDao.getByBatchId(batchId).filter { it.ndc == ndc }
        if (bottles.isEmpty()) return null
        val sealedCount = bottles.count { (it.bottleQty ?: 0) > 0 }
        val anchor = bottles.firstOrNull { (it.bottleQty ?: 0) > 0 } ?: bottles.first()
        return ActiveNdc(
            ndc = drug.ndc,
            drugName = drug.drugName ?: "",
            bucket = _bucketId.value.orEmpty(),
            batchNo = anchor.lotNo.orEmpty(),
            expiry = anchor.expiry.orEmpty(),
            pillsPerBottle = drug.packageQty ?: 0,
            bottles = bottles.sumOf { it.bottleQty ?: 0 },
            openPills = bottles.sumOf { it.looseQty ?: 0 },
            isHazardous = drug.isHazardous,
            // The bottle count spans multiple sealed transactions, so +/- can't be
            // attributed to one — guard persistActive from clobbering them.
            aggregated = sealedCount > 1,
        )
    }

    /**
     * Refresh the active card's open-pill total from the DB. Called when the screen
     * resumes (e.g. returning from the SCAN PILLS loose-count flow) so newly counted
     * open pills appear in the card's pills total. Only [ActiveNdc.openPills] is
     * touched so a concurrent +/- bottle edit isn't clobbered.
     */
    fun refreshActiveOpenPills() {
        val active = _activeNdc.value ?: return
        val batchId = resolvedBatchId.value
        if (batchId == 0L) return
        viewModelScope.launch {
            try {
                val open = openPillsTotal(batchId, active.ndc)
                _activeNdc.update { it?.copy(openPills = open) }
            } catch (e: Exception) {
                logger.e("INV_SCAN refreshActiveOpenPills failed", e, event = LogEvent.INVENTORY_COUNT_FAILED)
            }
        }
    }

    /**
     * Persists the edited "Edit Details" rows back onto their source transactions,
     * then refreshes the active card from the database.
     *
     * Each row is keyed by its [EditBatchRow.txnId]; sealed rows write [bottleQty]
     * and open rows write [looseQty]. A transaction present in neither list (its
     * rows were removed via the trash icon) is soft-deleted. A transaction that
     * survives in only one list has its other quantity cleared.
     */
    fun saveEditDetails(sealed: List<EditBatchRow>, open: List<EditBatchRow>) {
        val active = _activeNdc.value ?: return
        val batchId = resolvedBatchId.value
        if (batchId == 0L) return
        viewModelScope.launch {
            try {
                // txnId → (newBottleQty, newLooseQty); null = field cleared/removed.
                val edits = HashMap<Long, Pair<Int?, Int?>>()
                sealed.forEach { row ->
                    val prev = edits[row.txnId]
                    edits[row.txnId] = row.qty to prev?.second
                }
                open.forEach { row ->
                    val prev = edits[row.txnId]
                    edits[row.txnId] = (prev?.first) to row.qty
                }

                val originalTxns = stockBottleInfoDao.getByBatchId(batchId)
                    .filter { it.ndc == active.ndc }
                for (dto in originalTxns) {
                    val edit = edits[dto.bottleId]
                    if (edit == null) {
                        // Both rows removed — drop the bottle line.
                        stockBottleInfoDao.delete(dto.bottleId)
                        auditLogger.logOutsideTour(
                            LineDeleted(
                                batchId = batchId,
                                lineId = dto.bottleId,
                                ndc = dto.ndc,
                                lot = dto.lotNo,
                                expiry = dto.expiry,
                                oldBottleQty = dto.bottleQty,
                                oldLooseQty = dto.looseQty
                            )
                        )
                        continue
                    }
                    val entity = stockBottleInfoDao.getById(dto.bottleId) ?: continue
                    val newBottleQty = edit.first
                    val newLooseQty = edit.second
                    if ((newBottleQty ?: 0) <= 0 && (newLooseQty ?: 0) <= 0) {
                        stockBottleInfoDao.delete(dto.bottleId)
                        auditLogger.logOutsideTour(
                            LineDeleted(
                                batchId = batchId,
                                lineId = dto.bottleId,
                                ndc = dto.ndc,
                                lot = dto.lotNo,
                                expiry = dto.expiry,
                                oldBottleQty = dto.bottleQty,
                                oldLooseQty = dto.looseQty
                            )
                        )
                    } else {
                        stockBottleInfoDao.update(
                            entity.copy(
                                bottleQty = newBottleQty,
                                looseQty = newLooseQty,
                                updatedAt = System.currentTimeMillis(),
                            )
                        )
                        // SAVE rewrites every row; only the ones whose numbers moved are edits.
                        if (dto.bottleQty != newBottleQty || dto.looseQty != newLooseQty) {
                            auditLogger.logOutsideTour(
                                LineEdited(
                                    batchId = batchId,
                                    lineId = dto.bottleId,
                                    lot = dto.lotNo,
                                    oldBottleQty = dto.bottleQty,
                                    newBottleQty = newBottleQty,
                                    oldLooseQty = dto.looseQty,
                                    newLooseQty = newLooseQty
                                )
                            )
                        }
                    }
                }

                // Refresh the active card from the DB so it reflects the edits as
                // batch totals (recent-counts list updates automatically via its
                // Flow). buildActiveTotals returns null when every row was removed.
                _activeNdc.value = buildActiveTotals(batchId, active.ndc)
                    ?.copy(serialNo = active.serialNo)
            } catch (e: Exception) {
                logger.e("INV_SCAN saveEditDetails failed", e, event = LogEvent.INVENTORY_COUNT_FAILED)
                _errorMessage.value = LocalizedError(R.string.batch_stock_count_save_failed)
            } finally {
                _editDetails.value = null
            }
        }
    }

    /* ─────────────────────────  Scan pills hand-off  ───────────────────────── */

    /**
     * SCAN PILLS hand-off (Path 1): the user wants to count loose/open pills.
     * We flush the active card's bottle count so it isn't lost, clear the staged
     * dispense txnId, and invoke [onReady] on the caller so it can open the loose count.
     *
     * Works with or without an active NDC. The loose count scans its own
     * container, so SCAN PILLS is always available.
     *
     * batchId is passed through as-is — including 0L when no batch has been
     * created yet — rather than creating it here. Tapping SCAN PILLS is not
     * itself a commitment to count; the batch is created when the loose count
     * is saved, same as [onBarcodeDetected] does for the NDC-scan path, so an
     * abandoned session never leaves an empty batch row.
     *
     * @param onReady Called with (batchId, bucketId, allowedNdcs) when ready to navigate.
     *   bucketId is this batch's bucket; a batch the loose count creates gets it.
     *   allowedNdcs is the set of NDCs the loose count may accept:
     *   - PMS batch → restrict to the full PMS-requested NDC set.
     *   - Manual batch → empty set (no restriction).
     *   An active NDC does NOT narrow this: its count is already persisted, so the
     *   user may scan a different container to count its loose pills.
     */
    fun onScanPillsForActive(onReady: (batchId: Long, bucketId: String?, allowedNdcs: Set<String>) -> Unit) {
        val active = _activeNdc.value
        viewModelScope.launch {
            try {
                // Flush the active bottle count first (if any) so it isn't lost
                // while the user is away counting pills.
                if (active != null) persistActive(active)

                // Do NOT create the batch here. SCAN PILLS only stages an intent to
                // count — the user may still back out of the loose count before
                // saving. Pass batchId through as-is (0L when no batch exists yet);
                // the loose count creates it when saved, mirroring onBarcodeDetected above.
                val batchId = resolvedBatchId.value

                // Build the NDC allowlist for the loose count.
                // Only PMS batches restrict which NDCs may be counted. An active card
                // does not narrow it — its count is already persisted above.
                val allowedNdcs: Set<String> = expectedNdcs ?: emptySet()

                logger.d("INV_SCAN onScanPillsForActive active=${active?.ndc} batchId=$batchId bucketId=${_bucketId.value} allowedNdcs=$allowedNdcs")
                onReady(batchId, _bucketId.value, allowedNdcs)
            } catch (e: Exception) {
                logger.e("INV_SCAN onScanPillsForActive failed", e, event = LogEvent.INVENTORY_COUNT_FAILED)
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

    fun confirmEndCount(note: String? = null) {
        FtueController.track(viewModelScope.launch {
            try {
                val batchId = resolvedBatchId.value
                // Guard: never complete a batch that has no committed NDC. Even if
                // END COUNT is somehow enabled with nothing scanned, leave the DB
                // untouched. We check committed rows (not just batchId) because a
                // batch row can exist after a scan that was cleared without ADD.
                val hasCommittedNdc = batchId != 0L && recentRows.value.isNotEmpty()
                if (hasCommittedNdc) {
                    stockBatchDao.markAsCompleted(batchId)
                    if (!note.isNullOrBlank()) {
                        stockBatchDao.updateNote(batchId, note)
                    }
                    val rows = recentRows.value
                    auditLogger.logOutsideTour(
                        BatchCompleted(
                            batchId = batchId,
                            ndcCount = rows.size,
                            totalPills = rows.sumOf { it.pills },
                            // A blank note isn't saved, so it counts as no note.
                            noteLength = note?.takeIf { it.isNotBlank() }?.length ?: 0
                        )
                    )
                    if (hl7EventHandler.connectionState.value) {
                        stockHl7Repository.sendPendingBatches(firstSendBatchId = batchId)
                    }
                } else {
                    logger.d("INV_SCAN confirmEndCount: no committed NDC — nothing to persist")
                }
            } catch (e: Exception) {
                logger.e("confirmEndCount failed", e, event = LogEvent.INVENTORY_COUNT_FAILED)
            } finally {
                _showEndCountDialog.value = false
                _batchEnded.value = true
            }
        })
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

/**
 * How long after the last +/- counter tick to wait before persisting. Long-press
 * ticks repeat every ~80ms; 300ms comfortably outlasts the gap between ticks, so
 * the write fires once after the user lets go.
 */
private const val COUNTER_PERSIST_DEBOUNCE_MS = 300L

/* ─────────────────────────  Helpers  ───────────────────────── */

/**
 * Group sealed transactions by drugId and convert to [RecentBatchRow]s, newest
 * first. "Pills" = bottleQty × packageQty + looseQty so the figure reads as
 * total pills across all bottles in this batch for that drug.
 */
/**
 * Merge counted rows (drugs with bottle/loose lines) with the batch's requested-drug headers.
 * Every counted row is kept as-is; each requested drug not yet counted is appended as a zero
 * placeholder so a PMS batch lists all its requested drugs by default. Ordered counted-first,
 * then placeholders alphabetically — the query already sorts each source by drug name.
 */
private fun mergeRecentRows(
    counted: List<RecentBatchRow>,
    requested: List<RequestedDrugDto>,
): List<RecentBatchRow> {
    val countedNdcs = counted.mapNotNull { it.ndc.takeIf { ndc -> ndc.isNotBlank() } }.toSet()
    val placeholders = requested
        .filter { !it.ndc.isNullOrBlank() && it.ndc !in countedNdcs }
        .distinctBy { it.ndc }
        .map { RecentBatchRow(ndc = it.ndc!!, drugName = it.drugName.orEmpty(), pills = 0, bottles = 0) }
    return counted + placeholders
}

private fun List<StockBottleInfoDto>.toRecentRows(): List<RecentBatchRow> {
    if (isEmpty()) return emptyList()
    return groupBy { it.drugId }
        .map { (_, bottles) ->
            val first = bottles.first()
            val totalBottles = bottles.sumOf { it.bottleQty ?: 0 }
            val packageQty = bottles.firstOrNull { it.packageQty != null }?.packageQty ?: 0
            val totalLoose = bottles.sumOf { it.looseQty ?: 0 }
            val totalPills = totalBottles * packageQty + totalLoose
            // Each opened/loose line is its own physical bottle (bottleQty stays 0 so it
            // doesn't add a sealed package to the pill total). Count those bottles toward
            // the displayed bottle count so an opened bottle shows as +1 bottle.
            val openedBottles = bottles.count { (it.looseQty ?: 0) > 0 }
            RecentBatchRow(
                ndc = first.ndc.orEmpty(),
                drugName = first.drugName.orEmpty(),
                pills = totalPills,
                bottles = totalBottles + openedBottles,
            )
        }
}

