package com.rite.pillcounting.feature.dispenseFlow.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rite.pillcounting.core.models.ScheduleCode
import com.rite.pillcounting.core.models.StepState
import com.rite.pillcounting.core.room.dao.DrugMasterDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.models.DrugMasterEntity
import com.rite.pillcounting.core.room.models.PillCountTxnEntity
import com.rite.pillcounting.core.room.models.enums.CountStatus
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.room.models.enums.TxnPriority
import com.rite.pillcounting.core.scanning.domain.data.IDrugRepository
import com.rite.pillcounting.core.scanning.domain.model.GetNdcRequestModel
import com.rite.pillcounting.core.utils.compose.ContainerStatus
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.dashboard.domain.model.KpiFilter
import com.rite.pillcounting.feature.dashboard.domain.model.QueueItem
import com.rite.pillcounting.feature.dispenseFlow.domain.model.DispenseFlowUiState
import com.rite.pillcounting.feature.dispenseFlow.domain.model.DispenseStage
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
) : ViewModel() {

    private val logger = AppLogger("DispenseFlowVM")

    private val _uiState = MutableStateFlow(DispenseFlowUiState())
    val uiState: StateFlow<DispenseFlowUiState> = _uiState.asStateFlow()

    private var countType: CountType = CountType.FIXED
    private var queueObserverJob: Job? = null

    // Timestamp of the last VIAL RX-mismatch toast, used to debounce repeated
    // misses on the same wrong vial.
    private var lastVialMismatchToastAt: Long = 0L

    fun setCountType(type: String) {
        countType = runCatching { CountType.valueOf(type) }.getOrDefault(CountType.FIXED)
        // Stock count has no RX label — start directly at container (NDC) scanning.
        val initialStage = if (countType == CountType.REGULAR) DispenseStage.PRE_NDC else DispenseStage.PRE_RX
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
                return@launch
            }
            val txn = pillCountTxnDao.getById(txnId)
            if (txn == null) {
                logger.w("HL7 init: txn $txnId not found in DB — falling back to PRE_RX")
                return@launch
            }
            val drug = txn.drugId?.let { drugMasterDao.getDrugById(it) }
            _uiState.update {
                it.copy(
                    stage = DispenseStage.PRE_NDC,
                    isFromHl7 = true,
                    txnId = txnId,
                    drugName = drug?.drugName ?: it.drugName,
                    ndc = drug?.ndc ?: it.ndc,
                    hl7ExpectedNdc = drug?.ndc,
                    rxNo = txn.rxNo,
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
                return@launch
            }
            val txn = pillCountTxnDao.getById(txnId)
            if (txn == null) {
                logger.w("Resume init: txn $txnId not found in DB — falling back to PRE_RX")
                return@launch
            }
            pillCountTxnDao.updateGlovesPresent(txnId, false)
            val drug = txn.drugId?.let { drugMasterDao.getDrugById(it) }

            if (txn.isNdcVerified == true) {
                // RX + container both confirmed — skip straight to pill counting.
                _uiState.update {
                    it.copy(
                        stage = DispenseStage.COUNTING,
                        txnId = txnId,
                        drugName = drug?.drugName ?: it.drugName,
                        ndc = drug?.ndc ?: it.ndc,
                        rxNo = txn.rxNo,
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
                        txnId = txnId,
                        drugName = drug?.drugName ?: it.drugName,
                        ndc = drug?.ndc ?: it.ndc,
                        hl7ExpectedNdc = drug?.ndc,
                        rxNo = txn.rxNo,
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

        viewModelScope.launch {
            try {
                val parsed = parseScanData(
                    preferenceHelper.getBarcodeRegex().toString(),
                    gtin14
                )
                val parsedNdc = parsed.ndcNo
                val qty = parsed.qty
                val rxNo = parsed.rxNo
                val bucket = parsed.bucket

                if (parsedNdc.isNullOrBlank() || qty.isNullOrBlank() || rxNo.isNullOrBlank()) {
                    _uiState.update { it.copy(isLoading = false, showInvalidScanDialog = true) }
                    return@launch
                }

                if (!bucket.isNullOrBlank()) {
                    _uiState.update { it.copy(selectedBucketId = bucket) }
                }

                // Check if this RX has an active local transaction (PARTIAL or ON_HOLD).
                val existingTxn = pillCountTxnDao.getActiveByRxNo(rxNo)
                if (existingTxn == null) {
                    _uiState.update { it.copy(isLoading = false, txnNotFoundToastTick = it.txnNotFoundToastTick + 1) }
                    return@launch
                }
                when (existingTxn.status) {
                    CountStatus.ON_HOLD -> {
                        _uiState.update { it.copy(isLoading = false, showOnHoldDialog = true) }
                        return@launch
                    }
                    CountStatus.PARTIAL -> {
                        // Auto-resume the existing transaction without asking.
                        val txnId = existingTxn.txnId
                        val drug = existingTxn.drugId?.let { drugMasterDao.getDrugById(it) }
                        pillCountTxnDao.updateGlovesPresent(txnId, false)
                        preferenceHelper.saveTxnId(txnId)
                        val targetStage = if (existingTxn.isNdcVerified == true) DispenseStage.COUNTING else DispenseStage.PRE_NDC
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                stage = targetStage,
                                txnId = txnId,
                                drugName = drug?.drugName ?: it.drugName,
                                ndc = drug?.ndc ?: it.ndc,
                                hl7ExpectedNdc = drug?.ndc,
                                rxNo = existingTxn.rxNo ?: rxNo,
                                qty = existingTxn.targetCount?.toString() ?: qty,
                                isHazardous = drug?.isHazardous ?: false,
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
    fun onNdcBarcodeRead(gtin14: String, imagePath: String?) {
        if (_uiState.value.stage != DispenseStage.PRE_NDC) return
        if (_uiState.value.isLoading) return
        if (gtin14.isBlank()) {
            _uiState.update { it.copy(showInvalidScanDialog = true) }
            return
        }

        _uiState.update { it.copy(isLoading = true) }

        viewModelScope.launch {
            try {
                // Batch PMS restriction: if an NDC allowlist is set, reject the
                // scan immediately before doing any DB/server lookup.
                val allowedNdcs = _uiState.value.allowedNdcs
                if (allowedNdcs.isNotEmpty()) {
                    // Resolve the NDC from the scanned GTIN-14 for comparison.
                    // Try local DB first; if not found, the raw value may already be an NDC.
                    val localForCheck = drugMasterDao.getDrugByGtin(gtin14)
                        ?: drugMasterDao.getDrugByNdc(gtin14)
                    val scannedNdc = localForCheck?.ndc ?: gtin14
                    if (scannedNdc !in allowedNdcs) {
                        logger.w("NDC scan rejected by allowlist: scanned=$gtin14 resolvedNdc=$scannedNdc allowed=$allowedNdcs")
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                ndcNotAllowedToastTick = it.ndcNotAllowedToastTick + 1,
                                ndcNotAllowedValue = scannedNdc,
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
                    // Show Sealed/Open sheet only for standalone stock-count scans
                    // (no batchId) where no txn exists yet. When coming from the
                    // batch "Scan Pills" flow the batchId is always set, so skip
                    // the sheet and advance straight to COUNTING.
                    val needsSheet = countType == CountType.REGULAR &&
                            _uiState.value.txnId == 0L &&
                            _uiState.value.batchId == 0L
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            ndcScannedValue = localDrug!!.ndc,
                            ndcDrugName = localDrug.drugName ?: it.drugName,
                            ndcPackageQty = localDrug.packageQty,
                            barcodeImagePath = imagePath ?: it.barcodeImagePath,
                            showNdcDetails = needsSheet,
                            isHazardous = localDrug.isHazardous,
                        )
                    }
                    logger.i("[HAZARDOUS] NDC scan → local DB: ndc=${localDrug!!.ndc} drug=${localDrug.drugName} isHazardous=${localDrug.isHazardous} needsSheet=$needsSheet")
                    if (!needsSheet) advanceToCountingStage()
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
                drugMasterDao.upsertPreservingId(
                    DrugMasterEntity(
                        ndc = drugInfo.ndc,
                        drugName = displayName,
                        drugType = drugInfo.drugType,
                        gtin = gtin14,
                        packageQty = drugInfo.qty,
                        isHazardous = drugInfo.isHazardous ?: false,
                        strength = drugInfo.strength,
                        dosageForm = drugInfo.dosageForm,
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
                            ndcDosageForm = drugInfo.dosageForm,
                            barcodeImagePath = imagePath ?: it.barcodeImagePath,
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

                val needsSheet = countType == CountType.REGULAR &&
                        _uiState.value.txnId == 0L &&
                        _uiState.value.batchId == 0L
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        ndcScannedValue = drugInfo.ndc,
                        ndcDrugName = displayName,
                        ndcPackageQty = drugInfo.qty,
                        ndcStrength = drugInfo.strength,
                        ndcDosageForm = drugInfo.dosageForm,
                        barcodeImagePath = imagePath ?: it.barcodeImagePath,
                        showNdcDetails = needsSheet,
                        isHazardous = drugInfo.isHazardous ?: false,
                    )
                }
                logger.i("[HAZARDOUS] NDC scan → server match: ndc=${drugInfo.ndc} drug=$displayName isHazardous=${drugInfo.isHazardous ?: false} needsSheet=$needsSheet")
                if (!needsSheet) advanceToCountingStage()
            } catch (e: Exception) {
                logger.e("NDC barcode processing failed", e)
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    /**
     * Called when the user confirms the RX bottomsheet (manual flow only).
     * Creates a PARTIAL transaction for the scanned drug and advances to PRE_NDC.
     */
    fun onRxConfirmed() {
        val state = _uiState.value
        if (state.ndc.isBlank()) return

        viewModelScope.launch {
            val drugId = drugMasterDao.upsertPreservingId(
                DrugMasterEntity(ndc = state.ndc, drugName = state.drugName)
            )
            val qtyInt = state.qty?.toIntOrNull() ?: 0
            val txn = PillCountTxnEntity(
                localId = preferenceHelper.getLocalId(),
                drugId = drugId,
                countType = countType,
                status = CountStatus.PARTIAL,
                expiry = null,
                lotNo = null,
                barcodeImage = state.barcodeImagePath,
                isNdcVerified = false,
                targetCount = qtyInt,
                bucketId = state.selectedBucketId.ifBlank { null },
                rxNo = state.rxNo?.ifBlank { null },
            )
            val newTxnId = pillCountTxnDao.upsertPreservingId(txn)
            preferenceHelper.saveTxnId(newTxnId)
            _uiState.update {
                it.copy(
                    stage = DispenseStage.PRE_NDC,
                    showRxDetails = false,
                    txnId = newTxnId,
                )
            }
            logger.i("[HAZARDOUS] RX confirmed: ndc=${state.ndc} drug=${state.drugName} isHazardous=${state.isHazardous} txn=$newTxnId → PRE_NDC")
        }
    }

    /** Dismiss the RX bottomsheet without creating a transaction. */
    fun onRxCancelled() {
        _uiState.update {
            it.copy(
                showRxDetails = false,
                drugName = "",
                ndc = "",
                rxNo = null,
                qty = null,
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
        val needsSheet = countType == CountType.REGULAR &&
                _uiState.value.txnId == 0L &&
                _uiState.value.batchId == 0L
        _uiState.update {
            it.copy(
                showNdcEquivalenceDialog = false,
                isSubstituteConfirmed = true,
                showNdcDetails = needsSheet,
            )
        }
        if (!needsSheet) {
            viewModelScope.launch { advanceToCountingStage() }
        }
    }

    private suspend fun advanceToCountingStage() {
        val state = _uiState.value
        val txnId = state.txnId

        if (txnId == 0L) {
            // No pre-existing txn: batch "Scan Pills" flow where no active NDC
            // was staged. Create a fresh PARTIAL txn scoped to this batch so
            // the pill-count step has a real txn to accumulate counts against.
            if (countType != CountType.REGULAR || state.batchId == 0L) return
            val ndc = state.ndcScannedValue.ifBlank { state.ndc }
            val drugId = drugMasterDao.upsertPreservingId(
                DrugMasterEntity(
                    ndc = ndc,
                    drugName = state.ndcDrugName.ifBlank { state.drugName },
                    isHazardous = state.isHazardous,
                    strength = state.ndcStrength,
                    dosageForm = state.ndcDosageForm,
                )
            )
            val newTxnId = pillCountTxnDao.upsertPreservingId(
                PillCountTxnEntity(
                    localId = preferenceHelper.getLocalId(),
                    drugId = drugId,
                    countType = CountType.REGULAR,
                    status = CountStatus.PARTIAL,
                    isNdcVerified = true,
                    batchId = state.batchId,
                    barcodeImage = state.barcodeImagePath,
                )
            )
            preferenceHelper.saveTxnId(newTxnId)
            _uiState.update {
                it.copy(stage = DispenseStage.COUNTING, showNdcDetails = false, txnId = newTxnId)
            }
            logger.i("[HAZARDOUS] NDC auto-confirmed (batch): isHazardous=${state.isHazardous} txn=$newTxnId batchId=${state.batchId} → COUNTING")
            return
        }

        val txn = pillCountTxnDao.getById(txnId) ?: return
        val isSubstitute = state.isSubstituteConfirmed
        val substitutedDrugId = if (isSubstitute && state.ndcScannedValue.isNotBlank()) {
            drugMasterDao.upsertPreservingId(
                DrugMasterEntity(
                    ndc = state.ndcScannedValue,
                    drugName = state.ndcDrugName.ifBlank { state.drugName },
                    drugType = state.ndcDrugType,
                    packageQty = state.ndcPackageQty,
                    isHazardous = state.isHazardous,
                    strength = state.ndcStrength,
                    dosageForm = state.ndcDosageForm,
                )
            )
        } else null
        pillCountTxnDao.update(
            txn.copy(
                isNdcVerified = true,
                isSubstitute = isSubstitute,
                substitutedDrugId = substitutedDrugId,
                barcodeImage = state.barcodeImagePath,
            )
        )
        _uiState.update { it.copy(stage = DispenseStage.COUNTING, showNdcDetails = false) }
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
                ndcDosageForm = null,
            )
        }
    }

    /**
     * Called when the user confirms the NDC popup. Behavior diverges based on
     * whether we entered via the manual flow or via HL7:
     *
     *  - Manual flow: a txn was created at RX confirm. Update it with
     *    `isNdcVerified = true` and advance to COUNTING.
     *  - HL7 flow: a PMS-created txn already exists. Update it with
     *    `isNdcVerified = true`, capture substitute drug info if applicable
     *    (the original drugId stays as the expected drug; substitutedDrugId
     *    points at the actual scanned drug), and advance to COUNTING.
     */
    fun onNdcConfirmed() {
        val state = _uiState.value
        val txnId = state.txnId

        // Stock count: no RX scan happened, so no txn exists yet. Create one now.
        // SEALED bottles are immediately complete — bottleQty = 1, status = COMPLETED,
        // then navigate back to the batch. OPENED bottles are PARTIAL and proceed to
        // COUNTING so the user can count pills.
        if (countType == CountType.REGULAR && txnId == 0L) {
            viewModelScope.launch {
                val isSealed = state.selectedContainerStatus == ContainerStatus.SEALED
                val ndc = state.ndcScannedValue.ifBlank { state.ndc }
                val drugId = drugMasterDao.upsertPreservingId(
                    DrugMasterEntity(
                        ndc = ndc,
                        drugName = state.ndcDrugName.ifBlank { state.drugName },
                        isHazardous = state.isHazardous,
                        strength = state.ndcStrength,
                        dosageForm = state.ndcDosageForm,
                    )
                )
                val txn = PillCountTxnEntity(
                    localId = preferenceHelper.getLocalId(),
                    drugId = drugId,
                    countType = countType,
                    status = if (isSealed) CountStatus.COMPLETED else CountStatus.PARTIAL,
                    expiry = null,
                    lotNo = null,
                    barcodeImage = state.barcodeImagePath,
                    isNdcVerified = true,
                    targetCount = null,
                    bucketId = state.selectedBucketId.ifBlank { null },
                    rxNo = null,
                    batchId = state.batchId.takeIf { it != 0L },
                    bottleQty = if (isSealed) 1 else 0,
                )
                val newTxnId = pillCountTxnDao.upsertPreservingId(txn)
                preferenceHelper.saveTxnId(newTxnId)
                if (isSealed) {
                    _uiState.update {
                        it.copy(
                            showNdcDetails = false,
                            txnId = newTxnId,
                            navigateToBatchId = state.batchId.takeIf { it != 0L } ?: newTxnId,
                        )
                    }
                    logger.i("[HAZARDOUS] Stock count SEALED: isHazardous=${state.isHazardous} txn=$newTxnId batchId=${state.batchId}")
                } else {
                    _uiState.update {
                        it.copy(stage = DispenseStage.COUNTING, showNdcDetails = false, txnId = newTxnId)
                    }
                    logger.i("[HAZARDOUS] Stock count OPENED: isHazardous=${state.isHazardous} txn=$newTxnId batchId=${state.batchId} → COUNTING")
                }
            }
            return
        }

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
                        dosageForm = state.ndcDosageForm,
                    )
                )
            } else null

            pillCountTxnDao.update(
                txn.copy(
                    isNdcVerified = true,
                    isSubstitute = isSubstitute,
                    substitutedDrugId = substitutedDrugId,
                    barcodeImage = state.barcodeImagePath
                )
            )
            _uiState.update {
                it.copy(stage = DispenseStage.COUNTING, showNdcDetails = false)
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
                ndcDosageForm = null,
                isSubstituteConfirmed = false,
            )
        }
    }

    fun onContainerStatusChanged(status: ContainerStatus) {
        _uiState.update { it.copy(selectedContainerStatus = status) }
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
            parseScanData(preferenceHelper.getBarcodeRegex().toString(), rawValue).rxNo?.trim().orEmpty()
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

    fun clearNavigateToBatch() {
        _uiState.update { it.copy(navigateToBatchId = null) }
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
            )
        }
    }

    private suspend fun hasPendingDispenseItems(): Boolean {
        val localId = preferenceHelper.getLocalId()
        return pillCountTxnDao.countPartialByCountType(
            countType = CountType.FIXED,
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
            pillCountTxnDao.observePartialByCountType(
                countType = CountType.FIXED,
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

private fun isControlledDrugType(drugType: String?): Boolean {
    val code = drugType?.trim()?.uppercase() ?: return false
    return ScheduleCode.entries.any { it.name == code }
}

