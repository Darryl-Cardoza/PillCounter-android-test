package com.rite.pillcounting.feature.dispenseFlow.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rite.pillcounting.R
import com.rite.pillcounting.core.models.isControlledDrugType
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.room.dao.DrugMasterDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.models.DrugMasterEntity
import com.rite.pillcounting.core.room.models.PillCountTxnEntity
import com.rite.pillcounting.core.room.models.enums.CountStatus
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.room.models.enums.TxnPriority
import com.rite.pillcounting.core.scanning.data.DrugImageDownloader
import com.rite.pillcounting.core.scanning.domain.data.IDrugRepository
import com.rite.pillcounting.core.scanning.domain.model.BottleInfo
import com.rite.pillcounting.core.scanning.domain.model.BottleInfoJson
import com.rite.pillcounting.core.scanning.domain.model.GetNdcRequestModel
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.dashboard.domain.model.KpiFilter
import com.rite.pillcounting.feature.dashboard.domain.model.QueueItem
import com.rite.pillcounting.feature.dispenseFlow.domain.model.DispenseFlowUiState
import com.rite.pillcounting.feature.dispenseFlow.domain.model.DispenseStage
import com.rite.pillcounting.feature.dispenseFlow.domain.model.StandaloneRxDraft
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import parseScanData
import javax.inject.Inject

/**
 * ViewModel for the merged dispense flow ([com.rite.pillcounting.feature.dispenseFlow.presentation.DispenseFlowScreen]).
 *
 * Three stages:
 *  - [DispenseStage.PRE_RX]   : no transaction yet, RX scanning active (manual flow).
 *  - [DispenseStage.PRE_NDC]  : transaction exists, waiting for NDC scan. Reached
 *                               either after RX confirmation (manual flow) or
 *                               directly from a PMS/HL7 notification.
 *  - [DispenseStage.COUNTING] : NDC verified — the rest of the workflow
 *                               (CONTAINER_INITIATE / VIAL / etc.) is driven by the
 *                               existing PillScanningViewModel.
 *
 * The PMS/HL7 entry path is hydrated by [initializeFromHl7Txn], which reads the
 * existing PMS-created transaction (drugId / targetCount / rxNo / expected NDC)
 * and skips straight to PRE_NDC. The NDC scan then validates against the
 * HL7-expected NDC with the same substitute-drug / mismatch dialogs the legacy
 * ScanBarCodeScreen flow had.
 *
 * PMS batch validation (for inventory batches with `requestIdFromPMS`) is NOT
 * handled here — that path goes through STOCK_COUNT / batch flows which still
 * use the legacy ScanBarCodeScreen.
 */
@HiltViewModel
class DispenseFlowViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val drugRepository: IDrugRepository,
    private val drugMasterDao: DrugMasterDao,
    private val preferenceHelper: PreferenceHelper,
    private val pillCountTxnDao: PillCountTxnDao,
    private val drugImageDownloader: DrugImageDownloader,
) : ViewModel() {

    private val logger = AppLogger("DispenseFlowVM")

    private val _uiState = MutableStateFlow(DispenseFlowUiState())
    val uiState: StateFlow<DispenseFlowUiState> = _uiState.asStateFlow()

    /**
     * One-shot event carrying the batchId committed by
     * `PillScanningViewModel.flushStagedDetails` at All Done for a deferred
     * stock-count session. The screen forwards it to the previous back-stack
     * entry's SavedStateHandle so `InventoryScanViewModel` can adopt it and
     * re-subscribe its Recent Counts list to the just-created batch. Cleared
     * via [consumeLazilyCreatedStockBatchId] after the screen hands it off.
     */
    private val _lazilyCreatedStockBatchId = MutableStateFlow<Long?>(null)
    val lazilyCreatedStockBatchId: StateFlow<Long?> = _lazilyCreatedStockBatchId.asStateFlow()

    /** Called from the screen after PillScanningVM commits a deferred stock session. */
    fun publishStockCountBatchId(batchId: Long) {
        if (batchId != 0L) _lazilyCreatedStockBatchId.value = batchId
    }

    /**
     * Clears the [lazilyCreatedStockBatchId] event after the screen has published
     * it to `NavController.previousBackStackEntry.savedStateHandle`. Keeps the
     * event one-shot so subsequent state emissions don't re-trigger the publish.
     */
    fun consumeLazilyCreatedStockBatchId() {
        _lazilyCreatedStockBatchId.value = null
    }

    private var isDispense: Boolean = true
    private var queueObserverJob: Job? = null

    // Timestamp of the last VIAL RX-mismatch toast, used to debounce repeated
    // misses on the same wrong vial.
    private var lastVialMismatchToastAt: Long = 0L

    fun setCountType(type: String) {
        val typeEnum = runCatching { CountType.valueOf(type) }.getOrDefault(CountType.FIXED)
        isDispense = typeEnum == CountType.FIXED
        // Stock count has no RX label — start directly at container (NDC) scanning.
        val initialStage = if (!isDispense) DispenseStage.PRE_NDC else DispenseStage.PRE_RX
        _uiState.update { it.copy(scanType = type, stage = initialStage) }
    }

    fun setBatchId(batchId: Long) {
        if (batchId != 0L) _uiState.update { it.copy(batchId = batchId) }
    }

    /**
     * HL7 toggle from the portal (cached in prefs). When disabled, the dispense
     * flow runs in a count-only mode: no RX/NDC barcode scanning, only the live
     * pill-count circle is shown.
     */
    fun isHl7Enabled(): Boolean = preferenceHelper.isHl7Enabled()

    /**
     * Hydrate the screen for an HL7/PMS-initiated dispense. Reads the
     * PMS-created transaction (saved by [com.rite.pillcounting.feature.hl7.data.repository.Hl7Repository])
     * and jumps straight to [DispenseStage.PRE_NDC], with:
     *  - `hl7ExpectedNdc` populated so NDC scan can validate / detect substitutes
     *  - `drugName` / `rxNo` / `targetCount` from the HL7 record
     *  - `isFromHl7 = true` so the substitute path is offered on mismatch
     *
     * If the txn isn't found (e.g. it was cleared between notification post and
     * tap), the screen falls back to the manual PRE_RX flow.
     */
    fun initializeFromHl7Txn() {
        viewModelScope.launch {
            val txnId = preferenceHelper.getTxnId()
            if (txnId == 0L) {
                logger.w("HL7 init requested but no txnId in preferences — falling back to PRE_RX")
                _uiState.update { it.copy(initResolved = true) }
                return@launch
            }
            val txn = pillCountTxnDao.getById(txnId)
            if (txn == null) {
                logger.w("HL7 init: txn $txnId not found in DB — falling back to PRE_RX")
                _uiState.update { it.copy(initResolved = true) }
                return@launch
            }
            val drug = txn.drugId?.let { drugMasterDao.getDrugById(it) }
            _uiState.update {
                it.copy(
                    stage = DispenseStage.PRE_NDC,
                    initResolved = true,
                    isFromHl7 = true,
                    txnId = txnId,
                    drugName = drug?.drugName ?: it.drugName,
                    ndc = drug?.ndc ?: it.ndc,
                    hl7ExpectedNdc = drug?.ndc,
                    rxNo = txn.rxNo,
                    refillNo = txn.refillNo,
                    qty = txn.targetCount?.toString(),
                    isHazardous = drug?.isHazardous ?: false,
                )
            }
            logger.i("[HAZARDOUS] HL7 init: txn=$txnId drug=${drug?.drugName} expectedNdc=${drug?.ndc} isHazardous=${drug?.isHazardous ?: false}")
        }
    }

    /**
     * Hydrate the screen when resuming an existing partial transaction that has
     * not yet had its NDC verified. Reads the saved txnId from preferences, loads
     * the drug info, and jumps straight to [DispenseStage.PRE_NDC] so the user
     * only needs to scan the container — the RX stage is skipped entirely.
     */
    fun initializeFromResumedTxn() {
        viewModelScope.launch {
            val txnId = preferenceHelper.getTxnId()
            if (txnId == 0L) {
                logger.w("Resume init requested but no txnId in preferences — falling back to PRE_RX")
                _uiState.update { it.copy(initResolved = true) }
                return@launch
            }
            val txn = pillCountTxnDao.getById(txnId)
            if (txn == null) {
                logger.w("Resume init: txn $txnId not found in DB — falling back to PRE_RX")
                _uiState.update { it.copy(initResolved = true) }
                return@launch
            }
            pillCountTxnDao.updateGlovesPresent(txnId, false)
            val drug = txn.drugId?.let { drugMasterDao.getDrugById(it) }

            if (txn.isNdcVerified == true) {
                // RX + container both confirmed — skip straight to pill counting.
                _uiState.update {
                    it.copy(
                        stage = DispenseStage.COUNTING,
                        initResolved = true,
                        txnId = txnId,
                        drugName = drug?.drugName ?: it.drugName,
                        ndc = drug?.ndc ?: it.ndc,
                        rxNo = txn.rxNo,
                        refillNo = txn.refillNo,
                        qty = txn.targetCount?.toString(),
                        isHazardous = drug?.isHazardous ?: false,
                    )
                }
                logger.i("[HAZARDOUS] Resume init (NDC verified): txn=$txnId drug=${drug?.drugName} isHazardous=${drug?.isHazardous ?: false} — jumping to COUNTING")
            } else {
                // Container not yet scanned — land in PRE_NDC so the user only scans the container.
                _uiState.update {
                    it.copy(
                        stage = DispenseStage.PRE_NDC,
                        initResolved = true,
                        txnId = txnId,
                        drugName = drug?.drugName ?: it.drugName,
                        ndc = drug?.ndc ?: it.ndc,
                        hl7ExpectedNdc = drug?.ndc,
                        rxNo = txn.rxNo,
                        refillNo = txn.refillNo,
                        qty = txn.targetCount?.toString(),
                        isHazardous = drug?.isHazardous ?: false,
                    )
                }
                logger.i("[HAZARDOUS] Resume init (NDC pending): txn=$txnId drug=${drug?.drugName} expectedNdc=${drug?.ndc} isHazardous=${drug?.isHazardous ?: false}")
            }
        }
    }

    /**
     * Process a raw barcode read while the user is in PRE_RX. Parses out NDC / RX /
     * qty using the configured regex, validates, then looks up the drug locally
     * first and falls back to the server. On success surfaces the RX bottomsheet.
     */
    fun onRxBarcodeRead(gtin14: String, imagePath: String?) {
        if (_uiState.value.stage != DispenseStage.PRE_RX &&
            _uiState.value.stage != DispenseStage.QUEUE) return
        // Advance from QUEUE to PRE_RX so the rest of the RX logic works normally.
        if (_uiState.value.stage == DispenseStage.QUEUE) {
            _uiState.update { it.copy(stage = DispenseStage.PRE_RX) }
        }
        if (_uiState.value.isLoading) return
        if (gtin14.isBlank()) {
            _uiState.update { it.copy(showInvalidScanDialog = true) }
            return
        }

        _uiState.update { it.copy(isLoading = true) }

        val barcodeRegex = preferenceHelper.getBarcodeRegex().orEmpty()
        if (barcodeRegex.isBlank()) {
            _uiState.update { it.copy(isLoading = false, showInvalidScanDialog = true) }
            return
        }

        viewModelScope.launch {
            try {
                val parsed = parseScanData(
                    barcodeRegex,
                    gtin14
                )
                val parsedNdc = parsed.ndcNo
                val qty = parsed.qty
                val rxNo = parsed.rxNo
                val refillNo = parsed.refillNo
                val bucket = parsed.bucket

                val qtyInt = qty?.toIntOrNull()
                if (parsedNdc.isNullOrBlank() || qty.isNullOrBlank() || rxNo.isNullOrBlank() || qtyInt == null) {
                    _uiState.update { it.copy(isLoading = false, showInvalidScanDialog = true) }
                    return@launch
                }

                // Block re-dispensing a fill that's already gone out. getActiveByRxNo (below)
                // only matches PARTIAL/ON_HOLD, so without this check a COMPLETED Rx would
                // silently fall through to "no active txn" and spawn a brand new PARTIAL txn
                // on every rescan. Keyed by (rxNo, refillNo) when the label carries a fill
                // number, so a genuinely new refill isn't blocked by the prior fill's
                // COMPLETED status — only falls back to the bare rxNo lookup when the label
                // doesn't encode a refill number at all.
                val mostRecentTxn = if (!refillNo.isNullOrBlank()) {
                    pillCountTxnDao.getByRxNoAndFillNo(rxNo, refillNo)
                } else {
                    pillCountTxnDao.getMostRecentByRxNo(rxNo)
                }
                if (mostRecentTxn?.status == CountStatus.COMPLETED || mostRecentTxn?.status == CountStatus.FORCE_COMPLETED) {
                    _uiState.update {
                        it.copy(isLoading = false, rxAlreadyCompletedToastTick = it.rxAlreadyCompletedToastTick + 1)
                    }
                    return@launch
                }

                // Check if this RX has an active local transaction (PARTIAL or ON_HOLD).
                val existingTxn = pillCountTxnDao.getActiveByRxNo(rxNo)
                if (existingTxn == null) {
                    // No PMS-created transaction found. In standalone mode, no PMS will
                    // ever send this txn, so the app is expected to originate the
                    // dispense itself off the scanned Rx label, rather than wait on an
                    // HL7 order that will never arrive.
                    if (!preferenceHelper.isStandaloneMode()) {
                        _uiState.update { it.copy(isLoading = false, txnNotFoundToastTick = it.txnNotFoundToastTick + 1) }
                        return@launch
                    }
                    // Only stage the label here. The txn is created on Proceed, so
                    // cancelling or backing out leaves nothing in the DB.
                    showStandaloneRxSheet(
                        parsedNdc = parsedNdc,
                        rxNo = rxNo,
                        refillNo = refillNo,
                        bucket = bucket,
                        targetCount = qtyInt,
                    )
                    return@launch
                }
                when (existingTxn.status) {
                    CountStatus.ON_HOLD -> {
                        _uiState.update { it.copy(isLoading = false, showOnHoldDialog = true) }
                        return@launch
                    }
                    CountStatus.PARTIAL -> {
                        // RX found in local DB. The transaction already exists, so
                        // we only prep it here (gloves reset, save txnId).
                        //
                        // If the container/NDC scan is already done
                        // (isNdcVerified), skip the RX verification sheet and land
                        // the user directly on the transaction's current step
                        // (COUNTING). Otherwise show the sheet and defer the
                        // advance to PRE_NDC until the user taps Proceed.
                        val txnId = existingTxn.txnId
                        val drug = existingTxn.drugId?.let { drugMasterDao.getDrugById(it) }
                        pillCountTxnDao.updateGlovesPresent(txnId, false)
                        preferenceHelper.saveTxnId(txnId)
                        val ndcAlreadyVerified = existingTxn.isNdcVerified == true
                        val targetStage = if (ndcAlreadyVerified) DispenseStage.COUNTING else DispenseStage.PRE_NDC
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                // NDC already scanned → go straight to COUNTING.
                                // NDC pending → show the sheet, advance on Proceed.
                                stage = if (ndcAlreadyVerified) targetStage else it.stage,
                                showRxDetails = !ndcAlreadyVerified,
                                pendingRxResumeStage = if (ndcAlreadyVerified) null else targetStage,
                                txnId = txnId,
                                drugName = drug?.drugName.orEmpty(),
                                ndc = drug?.ndc.orEmpty(),
                                hl7ExpectedNdc = drug?.ndc,
                                rxNo = existingTxn.rxNo ?: rxNo,
                                refillNo = existingTxn.refillNo ?: refillNo,
                                qty = existingTxn.targetCount?.toString() ?: qty,
                                isHazardous = drug?.isHazardous ?: false,
                                // Surface the drug's strength on the RX verification
                                // sheet. Overwritten with the API value once the NDC
                                // is scanned in PRE_NDC.
                                ndcStrength = drug?.strength,
                                drugImage = drug?.drugImagePath.orEmpty(),
                                selectedBucketId = existingTxn.bucketId.orEmpty(),
                            )
                        }
                        return@launch
                    }
                    else -> {
                        _uiState.update { it.copy(isLoading = false, txnNotFoundToastTick = it.txnNotFoundToastTick + 1) }
                        return@launch
                    }
                }
            } catch (e: Exception) {
                logger.e("RX barcode processing failed", e)
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    /**
     * Resolves the drug for a standalone Rx label and shows the verify-Rx sheet.
     * Writes nothing to pill_count_txn — [onRxConfirmed] creates the txn on Proceed.
     */
    private suspend fun showStandaloneRxSheet(
        parsedNdc: String,
        rxNo: String,
        refillNo: String?,
        bucket: String?,
        targetCount: Int,
    ) {
        val drug = drugMasterDao.getDrugByNdc(parsedNdc)
            ?: drugMasterDao.getDrugByGtin(parsedNdc)
            ?: resolveNdcFromServer(parsedNdc)?.let { drugMasterDao.getDrugByNdc(it) }
        if (drug == null) {
            logger.w("Standalone dispense: drug could not be resolved for ndc=$parsedNdc rxNo=$rxNo — staging Rx without drug details")
        }
        // Every field is assigned outright, never falling back to the last scan's value.
        _uiState.update {
            it.copy(
                isLoading = false,
                showRxDetails = true,
                pendingStandaloneRx = StandaloneRxDraft(
                    drugId = drug?.drugId,
                    rxNo = rxNo,
                    refillNo = refillNo,
                    bucket = bucket,
                    targetCount = targetCount,
                ),
                pendingRxResumeStage = null,
                txnId = 0L,
                drugName = drug?.drugName.orEmpty(),
                ndc = drug?.ndc.orEmpty(),
                hl7ExpectedNdc = drug?.ndc,
                rxNo = rxNo,
                refillNo = refillNo,
                qty = targetCount.toString(),
                isHazardous = drug?.isHazardous ?: false,
                ndcStrength = drug?.strength,
                drugImage = drug?.drugImagePath.orEmpty(),
                selectedBucketId = bucket.orEmpty(),
            )
        }
        logger.i("[HAZARDOUS] Standalone RX staged: ndc=$parsedNdc rx=$rxNo drug=${drug?.drugName} isHazardous=${drug?.isHazardous ?: false} — awaiting Proceed")
    }

    /**
     * Inserts the PARTIAL dispense txn for a staged standalone Rx label.
     * Called from [onRxConfirmed] only, never from the scan itself.
     *
     * @return the new txn's id.
     */
    private suspend fun createStandaloneDispenseTxn(draft: StandaloneRxDraft): Long {
        val newTxn = PillCountTxnEntity(
            localId = preferenceHelper.getLocalId(),
            drugId = draft.drugId,
            isDispense = true,
            targetCount = draft.targetCount,
            status = CountStatus.PARTIAL,
            isComingFromHL7 = false,
            isSynced = false,
            isNdcVerified = false,
            rxNo = draft.rxNo,
            refillNo = draft.refillNo,
            // Same RxNo-RefillNo composite HL7MessageBuilder sends as the order identifier
            // (Vivid ZUI-4 rxNumber / EyeCon ZUI-11 transactionOrderId) — PMS pulls dispense
            // images via getByTransactionOrderId keyed on that value. Without it, a
            // locally-scanned dispense's images 404 on that pull and, with local storage
            // off, are deleted after ACK with no way to re-fetch them.
            transactionOrderId = draft.refillNo?.takeIf { it.isNotBlank() }?.let { "${draft.rxNo}-$it" } ?: draft.rxNo,
            bucketId = draft.bucket,
        )
        return pillCountTxnDao.upsertPreservingId(newTxn)
    }

    /**
     * Resolves a scanned GTIN-14 to its canonical NDC via the server, caching the drug in
     * `drug_master` WITH the scanned GTIN. Used by the PMS allowlist check when a local lookup
     * fails (PMS-requested drugs are cached without a GTIN). Caching here means the main lookup
     * later in [onNdcBarcodeRead] resolves locally and takes the trust-local path — no second
     * server round-trip. Returns null when the server can't resolve the barcode.
     */
    private suspend fun resolveNdcFromServer(gtin14: String): String? {
        val drugInfo = try {
            drugRepository.getDrugInfoByNdc(
                GetNdcRequestModel(target_ndc = "", scanned_ndc = gtin14)
            )
        } catch (e: Exception) {
            logger.e("allowlist NDC resolve failed for gtin=$gtin14", e)
            null
        } ?: return null

        val imagePath = drugImageDownloader.downloadAndSave(
            url = drugInfo.imageUrl,
            drugName = drugInfo.genericName?.takeIf { it.isNotBlank() } ?: drugInfo.ndc,
        )
        drugMasterDao.upsertPreservingId(
            DrugMasterEntity(
                ndc = drugInfo.ndc,
                drugName = drugInfo.genericName?.takeIf { it.isNotBlank() } ?: "Unknown Drug",
                drugType = drugInfo.drugType,
                gtin = gtin14,
                packageQty = drugInfo.qty,
                isHazardous = drugInfo.isHazardous ?: false,
                strength = drugInfo.strength,
                dosageForm = drugInfo.dosageForm,
                drugImagePath = imagePath,
            )
        )
        return drugInfo.ndc
    }

    /**
     * Process a raw barcode read while the user is in PRE_NDC.
     *
     * Validation order, matching the legacy ScanBarcodeViewModel for parity:
     *   1. Local DB lookup. If [DispenseFlowUiState.hl7ExpectedNdc] is set and
     *      the scanned NDC matches it, we use the local entry directly.
     *   2. Server fallback. The request carries `target_ndc = hl7ExpectedNdc` so
     *      the server can flag substitute (generic-equivalent) drugs via
     *      `is_ndc_equivalent`.
     *   3. If the server reports a substitute, we show the equivalence
     *      confirmation dialog instead of the success popup. The user has to
     *      accept the substitute explicitly via [confirmSubstitute].
     */
    fun onNdcBarcodeRead(gtin14: String, imagePath: String?, firstBottle: BottleInfo? = null) {
        if (_uiState.value.stage != DispenseStage.PRE_NDC) return
        if (_uiState.value.isLoading) return
        if (gtin14.isBlank()) {
            _uiState.update { it.copy(showInvalidScanDialog = true) }
            return
        }

        _uiState.update { it.copy(isLoading = true, pendingFirstBottle = firstBottle) }

        viewModelScope.launch {
            try {
                // Batch PMS restriction: if an NDC allowlist is set, reject any
                // scan whose drug isn't in the batch's requested set.
                val allowedNdcs = _uiState.value.allowedNdcs
                if (allowedNdcs.isNotEmpty()) {
                    // Resolve the scanned GTIN-14 to its NDC for comparison. Try the local
                    // DB first (by GTIN, then treating the raw value as an NDC). PMS-requested
                    // drugs are cached WITHOUT a GTIN — the PMS request only carries the NDC —
                    // so a first-time container scan won't resolve locally. Fall back to the
                    // server (which also caches the drug WITH its GTIN) so the comparison uses
                    // the real NDC, not the raw GTIN. Without this, correct barcodes were
                    // wrongly rejected until the drug had first been scanned on the main
                    // stock-count screen (which is what seeded the GTIN).
                    val localForCheck = drugMasterDao.getDrugByGtin(gtin14)
                        ?: drugMasterDao.getDrugByNdc(gtin14)
                    val scannedNdc = localForCheck?.ndc ?: resolveNdcFromServer(gtin14)
                    if (scannedNdc == null || scannedNdc !in allowedNdcs) {
                        logger.w("NDC scan rejected by allowlist: scanned=$gtin14 resolvedNdc=$scannedNdc allowed=$allowedNdcs")
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                ndcNotAllowedToastTick = it.ndcNotAllowedToastTick + 1,
                                ndcNotAllowedValue = scannedNdc ?: gtin14,
                            )
                        }
                        return@launch
                    }
                }

                // Expected NDC comes from HL7 in the PMS flow, and from the RX
                // label the user just scanned in the manual flow. Either way,
                // the container scan has to match it (or be a server-flagged
                // substitute) before we'll show the success sheet.
                val expectedNdc = _uiState.value.hl7ExpectedNdc
                    ?.takeIf { it.isNotBlank() }
                    ?: _uiState.value.ndc.takeIf { it.isNotBlank() }

                // Try local DB first by GTIN (scanned barcode), falling back to
                // NDC for cases where the scanned value was already an NDC.
                // Only trust a local hit when there's no expected NDC, or when
                // the local row's NDC matches it exactly — otherwise fall
                // through to the server so substitutes get flagged.
                val localDrug = drugMasterDao.getDrugByGtin(gtin14)
                    ?: drugMasterDao.getDrugByNdc(gtin14)
                val trustLocal = localDrug != null &&
                        (expectedNdc.isNullOrBlank() || expectedNdc == localDrug.ndc)

                if (trustLocal) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            ndcScannedValue = localDrug!!.ndc,
                            ndcDrugName = localDrug.drugName ?: it.drugName,
                            ndcPackageQty = localDrug.packageQty,
                            showNdcDetails = false,
                            isHazardous = localDrug.isHazardous,
                        )
                    }
                    logger.i("[HAZARDOUS] NDC scan → local DB: ndc=${localDrug!!.ndc} drug=${localDrug.drugName} isHazardous=${localDrug.isHazardous}")
                    advanceToCountingStage()
                    return@launch
                }

                // Server lookup with the expected NDC as target so substitutes
                // get flagged via is_ndc_equivalent.
                val drugInfo = drugRepository.getDrugInfoByNdc(
                    GetNdcRequestModel(
                        target_ndc = expectedNdc.orEmpty(),
                        scanned_ndc = gtin14
                    )
                )
                if (drugInfo == null) {
                    _uiState.update {
                        it.copy(isLoading = false, showNdcNotFoundDialog = true)
                    }
                    return@launch
                }

                val displayName = drugInfo.genericName?.takeIf { it.isNotBlank() }
                    ?: "Unknown Drug"
                val drugImagePath = drugImageDownloader.downloadAndSave(
                    url = drugInfo.imageUrl,
                    drugName = drugInfo.genericName?.takeIf { it.isNotBlank() } ?: drugInfo.ndc,
                )
                drugMasterDao.upsertPreservingId(
                    DrugMasterEntity(
                        ndc = drugInfo.ndc,
                        drugName = displayName,
                        drugType = drugInfo.drugType,
                        gtin = gtin14.trim(),
                        packageQty = drugInfo.qty,
                        isHazardous = drugInfo.isHazardous ?: false,
                        strength = drugInfo.strength,
                        dosageForm = drugInfo.dosageForm,
                        drugImagePath = drugImagePath,
                    )
                )

                val isSubstitute = drugInfo.is_ndc_equivalent == true
                if (isSubstitute) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            ndcScannedValue = drugInfo.ndc,
                            ndcDrugName = displayName,
                            ndcPackageQty = drugInfo.qty,
                            ndcDrugType = drugInfo.drugType,
                            ndcStrength = drugInfo.strength,
                            drugImage = drugInfo.imageUrl ?: it.drugImage,
                            ndcDosageForm = drugInfo.dosageForm,
                            showNdcEquivalenceDialog = true,
                            isHazardous = drugInfo.isHazardous ?: false,
                        )
                    }
                    logger.i("[HAZARDOUS] NDC scan → substitute: scanned=$gtin14 serverNdc=${drugInfo.ndc} drug=$displayName isHazardous=${drugInfo.isHazardous ?: false}")
                    return@launch
                }

                // Hard mismatch: an expected NDC is set (from HL7 or from the
                // RX label) and the server didn't flag the scan as a
                // substitute. Surface a non-blocking toast and let the user
                // rescan — the popup-style dialog was too heavy for this case.
                if (!expectedNdc.isNullOrBlank() && drugInfo.ndc != expectedNdc) {
                    logger.w("[HAZARDOUS] NDC scan → mismatch: scanned=$gtin14 serverNdc=${drugInfo.ndc} expectedNdc=$expectedNdc isHazardous=${drugInfo.isHazardous ?: false}")
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            ndcMismatchToastTick = it.ndcMismatchToastTick + 1,
                        )
                    }
                    return@launch
                }

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        ndcScannedValue = drugInfo.ndc,
                        ndcDrugName = displayName,
                        ndcPackageQty = drugInfo.qty,
                        ndcStrength = drugInfo.strength,
                        drugImage = drugInfo.imageUrl ?: it.drugImage,
                        ndcDosageForm = drugInfo.dosageForm,
                        showNdcDetails = false,
                        isHazardous = drugInfo.isHazardous ?: false,
                    )
                }
                logger.i("[HAZARDOUS] NDC scan → server match: ndc=${drugInfo.ndc} drug=$displayName isHazardous=${drugInfo.isHazardous ?: false}")
                advanceToCountingStage()
            } catch (e: Exception) {
                logger.e("NDC barcode processing failed", e)
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    /**
     * Called when the user confirms the RX bottomsheet (manual flow only).
     * Resumes the existing txn, or creates the standalone one, then advances to PRE_NDC.
     */
    fun onRxConfirmed() {
        val state = _uiState.value

        // Scanned-RX resume path: the transaction already exists (resolved in
        // onRxBarcodeRead), so just advance to the remembered stage — do NOT
        // create a new transaction.
        state.pendingRxResumeStage?.let { resumeStage ->
            _uiState.update {
                it.copy(
                    stage = resumeStage,
                    showRxDetails = false,
                    pendingRxResumeStage = null,
                )
            }
            return
        }

        // Standalone Rx: the txn is created here, on Proceed. The draft is cleared first
        // so a second tap can't insert a second row.
        state.pendingStandaloneRx?.let { draft ->
            _uiState.update { it.copy(showRxDetails = false, pendingStandaloneRx = null) }
            viewModelScope.launch {
                try {
                    val txnId = createStandaloneDispenseTxn(draft)
                    preferenceHelper.saveTxnId(txnId)
                    _uiState.update { it.copy(stage = DispenseStage.PRE_NDC, txnId = txnId) }
                    logger.i("[HAZARDOUS] Standalone RX confirmed: rx=${draft.rxNo} drugId=${draft.drugId} isHazardous=${state.isHazardous} txn=$txnId → PRE_NDC")
                } catch (e: Exception) {
                    // Surface the failure the way the scan-time path used to.
                    logger.e("Standalone dispense txn creation failed", e)
                    _uiState.update { it.copy(error = e.message) }
                }
            }
            return
        }
    }

    /** Dismiss the RX bottomsheet without creating a transaction. */
    fun onRxCancelled() {
        // Clear every field the sheet renders — leftovers showed the previous Rx's
        // details on the next scan.
        _uiState.update {
            it.copy(
                showRxDetails = false,
                pendingRxResumeStage = null,
                pendingStandaloneRx = null,
                txnId = 0L,
                drugName = "",
                ndc = "",
                hl7ExpectedNdc = null,
                rxNo = null,
                refillNo = null,
                qty = null,
                ndcStrength = null,
                drugImage = "",
                selectedBucketId = "",
                isHazardous = false,
            )
        }
    }

    /**
     * Confirm a substitute (generic-equivalent) drug. Closes the equivalence
     * dialog, marks the substitute flag, and proceeds to the standard NDC
     * confirmation popup so the user sees the same fields they'd see for a
     * straight match.
     */
    fun confirmSubstitute() {
        _uiState.update {
            it.copy(
                showNdcEquivalenceDialog = false,
                isSubstituteConfirmed = true,
                showNdcDetails = false,
            )
        }
        viewModelScope.launch { advanceToCountingStage() }
    }

    private suspend fun advanceToCountingStage() {
        val state = _uiState.value
        val txnId = state.txnId

        if (txnId == 0L) {
            // No pre-existing txn: stock-count Scan-Pills flow. Fully deferred —
            // NO writes to batch / stock_txn / bottle_info until the user hits
            // All Done and confirms the popup. Back-out before commit leaves zero
            // rows behind. PillScanningViewModel.flushStagedDetails owns the
            // atomic commit at Done time using [stockDrugId] + [selectedBucketId].
            if (isDispense) return
            val ndc = state.ndcScannedValue.ifBlank { state.ndc }
            val drugId = drugMasterDao.getDrugIdByNdc(ndc)
                ?: drugMasterDao.upsertPreservingId(
                    DrugMasterEntity(
                        ndc = ndc,
                        drugName = state.ndcDrugName.ifBlank { state.drugName },
                        packageQty = state.ndcPackageQty,
                        isHazardous = state.isHazardous,
                        strength = state.ndcStrength,
                        drugImagePath = state.drugImage,
                        dosageForm = state.ndcDosageForm,
                    )
                )
            preferenceHelper.saveTxnId(0)
            // Preserve state.batchId / stockTxnId / stockBottleId. When the flow was
            // opened from the Batch Stock Count "Scan Pills" hand-off, batchId (and
            // possibly a pre-existing stockTxnId/stockBottleId) were seeded via
            // setBatchId — zeroing them here would make flushStagedDetails treat this
            // as a fresh deferred session and mint a duplicate batch on every commit
            // instead of appending onto the batch the user is continuing.
            _uiState.update {
                it.copy(
                    stage = DispenseStage.COUNTING,
                    showNdcDetails = false,
                    txnId = 0L,
                    stockDrugId = drugId,
                )
            }
            logger.i("[HAZARDOUS] NDC auto-confirmed (deferred stock): isHazardous=${state.isHazardous} drugId=$drugId batchId=${state.batchId} stockTxnId=${state.stockTxnId} stockBottleId=${state.stockBottleId} → COUNTING")
            return
        }

        val txn = pillCountTxnDao.getById(txnId) ?: return
        val isSubstitute = state.isSubstituteConfirmed
        val substitutedDrugId = if (isSubstitute && state.ndcScannedValue.isNotBlank()) {
            // The image was already downloaded during the NDC scan step (onNdcBarcodeRead).
            // Carry that path forward so the upsert doesn't overwrite it with null.
            val existingImagePath = drugMasterDao.getDrugByNdc(state.ndcScannedValue)?.drugImagePath
            drugMasterDao.upsertPreservingId(
                DrugMasterEntity(
                    ndc = state.ndcScannedValue,
                    drugName = state.ndcDrugName.ifBlank { state.drugName },
                    drugType = state.ndcDrugType,
                    packageQty = state.ndcPackageQty,
                    isHazardous = state.isHazardous,
                    strength = state.ndcStrength,
                    dosageForm = state.ndcDosageForm,
                    drugImagePath = existingImagePath,
                )
            )
        } else null
        // Stamp the first bottle scanned against this txn — only when the txn has no
        // bottle entries yet, so resuming an already-in-progress dispense (e.g. after
        // process death) doesn't clobber bottles already tracked via a rescan mid-count.
        val existingBottles = BottleInfoJson.decode(txn.bottleInfoListJson)
        val bottleInfoListJson = if (txn.isDispense && existingBottles.isEmpty() && state.pendingFirstBottle != null) {
            BottleInfoJson.encode(listOf(state.pendingFirstBottle.copy(txnId = txnId)))
        } else txn.bottleInfoListJson
        pillCountTxnDao.update(
            txn.copy(
                isNdcVerified = true,
                isSubstitute = isSubstitute,
                substitutedDrugId = substitutedDrugId,
                bottleInfoListJson = bottleInfoListJson,
            )
        )
        _uiState.update { it.copy(stage = DispenseStage.COUNTING, showNdcDetails = false, pendingFirstBottle = null) }
        logger.i("[HAZARDOUS] NDC auto-confirmed: txn=$txnId substitute=$isSubstitute isHazardous=${state.isHazardous} → COUNTING")
    }

    /** User confirmed they want to continue the existing PARTIAL transaction. */
    fun confirmContinueRx() {
        val txnId = _uiState.value.txnId
        viewModelScope.launch {
            val txn = pillCountTxnDao.getById(txnId) ?: return@launch
            val drug = txn.drugId?.let { drugMasterDao.getDrugById(it) }
            pillCountTxnDao.updateGlovesPresent(txnId, false)
            // Persist so pillVm.getDrugInfo() picks up the correct txn and restores
            // the saved workflow step (workflowStep column) from the DB.
            preferenceHelper.saveTxnId(txnId)
            // If NDC was already verified the user was mid-count: jump to COUNTING so
            // the pill-scanning VM can restore the exact workflow step from the DB.
            // If NDC was never verified: go to PRE_NDC so the user scans the container.
            val targetStage = if (txn.isNdcVerified == true) DispenseStage.COUNTING else DispenseStage.PRE_NDC
            _uiState.update {
                it.copy(
                    showContinueRxDialog = false,
                    stage = targetStage,
                    txnId = txnId,
                    drugName = drug?.drugName ?: it.drugName,
                    ndc = drug?.ndc ?: it.ndc,
                    hl7ExpectedNdc = drug?.ndc,
                    rxNo = txn.rxNo,
                    refillNo = txn.refillNo,
                    qty = txn.targetCount?.toString(),
                    isHazardous = drug?.isHazardous ?: false,
                )
            }
            logger.i("[HAZARDOUS] Continue RX: txn=$txnId isNdcVerified=${txn.isNdcVerified} isHazardous=${drug?.isHazardous ?: false} → $targetStage")
        }
    }

    /** User declined to continue the existing transaction; screen navigates to Dashboard. */
    fun dismissContinueRxDialog() {
        _uiState.update { it.copy(showContinueRxDialog = false, txnId = 0L) }
    }

    /** Dismiss the ON_HOLD blocking dialog; user stays on scan screen to try a different RX. */
    fun dismissOnHoldDialog() {
        _uiState.update { it.copy(showOnHoldDialog = false) }
    }

    fun dismissNdcEquivalenceDialog() {
        _uiState.update {
            it.copy(
                showNdcEquivalenceDialog = false,
                ndcScannedValue = "",
                ndcDrugName = "",
                ndcPackageQty = null,
                ndcDrugType = null,
                ndcStrength = null,
                drugImage = "",
                ndcDosageForm = null,
            )
        }
    }

    /**
     * Called when the user confirms the NDC popup for the dispense flow.
     * Updates the existing txn with `isNdcVerified = true`, captures
     * substitute drug info if applicable (the original drugId stays as the
     * expected drug; substitutedDrugId points at the actual scanned drug),
     * and advances to COUNTING. Stock count skips this sheet entirely.
     */
    fun onNdcConfirmed() {
        val state = _uiState.value
        val txnId = state.txnId

        if (txnId == 0L) return

        viewModelScope.launch {
            val txn = pillCountTxnDao.getById(txnId) ?: return@launch

            val isSubstitute = state.isSubstituteConfirmed
            val substitutedDrugId = if (isSubstitute && state.ndcScannedValue.isNotBlank()) {
                // Persist the substitute drug record so the existing
                // PillScanningViewModel.getDrugInfo flow can resolve it.
                drugMasterDao.upsertPreservingId(
                    DrugMasterEntity(
                        ndc = state.ndcScannedValue,
                        drugName = state.ndcDrugName.ifBlank { state.drugName },
                        drugType = state.ndcDrugType,
                        packageQty = state.ndcPackageQty,
                        isHazardous = state.isHazardous,
                        strength = state.ndcStrength,
                        drugImagePath = state.drugImage,
                        dosageForm = state.ndcDosageForm,
                    )
                )
            } else null

            val existingBottles = BottleInfoJson.decode(txn.bottleInfoListJson)
            val bottleInfoListJson = if (txn.isDispense && existingBottles.isEmpty() && state.pendingFirstBottle != null) {
                BottleInfoJson.encode(listOf(state.pendingFirstBottle.copy(txnId = txnId)))
            } else txn.bottleInfoListJson
            pillCountTxnDao.update(
                txn.copy(
                    isNdcVerified = true,
                    isSubstitute = isSubstitute,
                    substitutedDrugId = substitutedDrugId,
                    bottleInfoListJson = bottleInfoListJson,
                )
            )
            _uiState.update {
                it.copy(stage = DispenseStage.COUNTING, showNdcDetails = false, pendingFirstBottle = null)
            }
            logger.i("[HAZARDOUS] NDC confirmed (sheet): txn=$txnId substitute=$isSubstitute isHazardous=${state.isHazardous} → COUNTING")
        }
    }

    /** Dismiss the NDC popup; stays in PRE_NDC so the user can rescan. */
    fun onNdcCancelled() {
        _uiState.update {
            it.copy(
                showNdcDetails = false,
                ndcScannedValue = "",
                ndcDrugName = "",
                ndcPackageQty = null,
                ndcDrugType = null,
                ndcStrength = null,
                drugImage = "",
                ndcDosageForm = null,
                isSubstituteConfirmed = false,
            )
        }
    }

    fun dismissInvalidScanDialog() {
        _uiState.update { it.copy(showInvalidScanDialog = false) }
    }

    fun dismissNdcNotFoundDialog() {
        _uiState.update { it.copy(showNdcNotFoundDialog = false) }
    }

    /**
     * Process a vial barcode read during the VIAL step. The vial label carries
     * the same RX-label barcode the user scanned at PRE_RX, so we extract the RX
     * number and compare it to the active transaction's [DispenseFlowUiState.rxNo].
     *
     * Returns `true` when the RX matches — the caller then triggers the automatic
     * photo capture (mirroring a tap on the camera button) and should NOT resume
     * the analyzer. Returns `false` when the barcode can't be parsed or the RX
     * doesn't match; on a mismatch a debounced toast is surfaced and the caller
     * resumes the analyzer to keep scanning. Manual capture stays available
     * regardless.
     */
    fun onVialBarcodeRead(rawValue: String): Boolean {
        if (rawValue.isBlank()) return false
        val expectedRx = _uiState.value.rxNo?.trim().orEmpty()
        if (expectedRx.isEmpty()) return false

        // Pipe-delimited payloads are the RX-label template; otherwise the raw
        // value may already be the bare RX number.
        val scannedRx = if (rawValue.contains('|')) {
            val barcodeRegex = preferenceHelper.getBarcodeRegex().orEmpty()
            if (barcodeRegex.isBlank()) return false
            parseScanData(barcodeRegex, rawValue).rxNo?.trim().orEmpty()
        } else {
            rawValue.trim()
        }
        if (scannedRx.isEmpty()) return false

        val matched = scannedRx.equals(expectedRx, ignoreCase = true)
        if (!matched) {
            // The analyzer self-pauses then resumes on every miss, so the same
            // wrong vial would otherwise spam toasts — debounce them.
            val now = System.currentTimeMillis()
            if (now - lastVialMismatchToastAt > VIAL_MISMATCH_TOAST_COOLDOWN_MS) {
                lastVialMismatchToastAt = now
                _uiState.update { it.copy(vialRxMismatchToastTick = it.vialRxMismatchToastTick + 1) }
            }
            logger.w("VIAL barcode RX mismatch: scanned=$scannedRx expected=$expectedRx")
        }
        return matched
    }

    /**
     * Called from the screen when, during PRE_NDC, the user scans something
     * that looks like an RX label (pipe-delimited template, not a GTIN-14).
     * The legacy single-screen flow had no way to surface this; the merged
     * dispense flow now nudges the user with a toast instead of silently
     * dropping the read.
     */
    fun onRxScannedInNdcStage() {
        if (_uiState.value.stage != DispenseStage.PRE_NDC) return
        _uiState.update { it.copy(scanNdcToastTick = it.scanNdcToastTick + 1) }
    }

    /**
     * Called in the stock-count flow when the user scans an RX label instead
     * of the NDC container barcode. Shows a blocking dialog instead of a toast
     * because stock count never accepts RX labels — the user must rescan the
     * correct container.
     */
    fun onRxScannedInStockCount() {
        if (_uiState.value.stage != DispenseStage.PRE_NDC) return
        _uiState.update { it.copy(showRxScannedInStockCountDialog = true) }
    }

    fun dismissRxScannedInStockCountDialog() {
        _uiState.update { it.copy(showRxScannedInStockCountDialog = false) }
    }

    /**
     * Called from the batch "Scan Pills" hand-off to restrict NDC scanning to
     * the provided set. [onNdcBarcodeRead] will reject any scanned NDC that
     * isn't in this set (same mismatch-toast path as the HL7 flow).
     * Pass an empty set to remove the restriction (non-PMS / plain dispense).
     */
    fun setAllowedNdcs(ndcs: Set<String>) {
        _uiState.update { it.copy(allowedNdcs = ndcs) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    // ─────────────────────────── Queue mode ───────────────────────────

    fun enterQueueMode() {
        observeDispenseQueue()
        _uiState.update {
            DispenseFlowUiState(
                stage = DispenseStage.QUEUE,
                scanType = CountType.FIXED.name,
                queueItems = it.queueItems,
                selectedQueueFilter = it.selectedQueueFilter ?: KpiFilter.DISP_PENDING,
                initResolved = true,
            )
        }
    }

    fun resetToQueue() {
        observeDispenseQueue()
        _uiState.update {
            DispenseFlowUiState(
                stage = DispenseStage.QUEUE,
                scanType = CountType.FIXED.name,
                queueItems = it.queueItems,
                selectedQueueFilter = it.selectedQueueFilter ?: KpiFilter.DISP_PENDING,
                initResolved = true,
            )
        }
    }

    private suspend fun hasPendingDispenseItems(): Boolean {
        val localId = preferenceHelper.getLocalId()
        return pillCountTxnDao.countPartialByIsDispense(
            isDispense = true,
            partialStatus = CountStatus.PARTIAL,
            userLocalId = localId,
        ) > 0
    }

    fun resetToQueueOrNavigateDashboard() {
        viewModelScope.launch {
            if (hasPendingDispenseItems()) {
                resetToQueue()
            } else {
                _uiState.update { it.copy(navigateToDashboard = true) }
            }
        }
    }

    fun clearNavigateToDashboard() {
        _uiState.update { it.copy(navigateToDashboard = false) }
    }

    fun setQueueFilter(filter: KpiFilter?) {
        // Tabs always have one selection — clicking the active tab keeps it selected.
        if (filter != null) {
            _uiState.update { it.copy(selectedQueueFilter = filter) }
        }
    }

    fun resumeFromQueue(txnId: Long) {
        viewModelScope.launch {
            val txn = pillCountTxnDao.getById(txnId) ?: return@launch
            preferenceHelper.saveTxnId(txnId)
            pillCountTxnDao.updateGlovesPresent(txnId, false)
            val drug = txn.drugId?.let { drugMasterDao.getDrugById(it) }
            val targetStage = if (txn.isNdcVerified == true) DispenseStage.COUNTING else DispenseStage.PRE_NDC
            _uiState.update {
                it.copy(
                    stage = targetStage,
                    txnId = txnId,
                    drugName = drug?.drugName ?: it.drugName,
                    ndc = drug?.ndc ?: it.ndc,
                    hl7ExpectedNdc = drug?.ndc,
                    rxNo = txn.rxNo,
                    refillNo = txn.refillNo,
                    qty = txn.targetCount?.toString(),
                    isHazardous = drug?.isHazardous ?: false,
                )
            }
        }
    }

    private fun observeDispenseQueue() {
        queueObserverJob?.cancel()
        queueObserverJob = viewModelScope.launch(Dispatchers.IO) {
            val localId = preferenceHelper.getLocalId()
            pillCountTxnDao.observePartialByIsDispense(
                isDispense = true,
                partialStatus = CountStatus.PARTIAL,
                userLocalId = localId,
                type = StepState.TARGET_VERIFICATION,
            ).collect { txns ->
                val items = txns.map { txn ->
                    QueueItem.Dispense(
                        txn = txn,
                        isHazardous = txn.isHazardous,
                        isHighPriority = txn.priority == TxnPriority.High,
                        isControlled = isControlledDrugType(txn.drugType),
                    )
                }
                _uiState.update { it.copy(queueItems = items) }
            }
        }
    }
}

private const val VIAL_MISMATCH_TOAST_COOLDOWN_MS = 2000L

