package com.rite.pillcounting.feature.dispenseFlow.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rite.pillcounting.core.room.dao.DrugMasterDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.models.DrugMasterEntity
import com.rite.pillcounting.core.room.models.PillCountTxnEntity
import com.rite.pillcounting.core.room.models.enums.CountStatus
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.utils.compose.ContainerStatus
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.core.scanning.domain.data.IDrugRepository
import com.rite.pillcounting.core.scanning.domain.model.DrugInfo
import com.rite.pillcounting.core.scanning.domain.model.GetNdcRequestModel
import com.rite.pillcounting.feature.dispenseFlow.domain.model.DispenseFlowUiState
import com.rite.pillcounting.feature.dispenseFlow.domain.model.DispenseStage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
            logger.i("HL7 init: txn=$txnId drug=${drug?.drugName} expectedNdc=${drug?.ndc}")
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
                logger.i("Resume init (NDC verified): txn=$txnId — jumping to COUNTING")
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
                logger.i("Resume init (NDC pending): txn=$txnId drug=${drug?.drugName} expectedNdc=${drug?.ndc}")
            }
        }
    }

    /**
     * Process a raw barcode read while the user is in PRE_RX. Parses out NDC / RX /
     * qty using the configured regex, validates, then looks up the drug locally
     * first and falls back to the server. On success surfaces the RX bottomsheet.
     */
    fun onRxBarcodeRead(gtin14: String, imagePath: String?) {
        if (_uiState.value.stage != DispenseStage.PRE_RX) return
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
                    _uiState.update {
                        it.copy(isLoading = false, txnNotFoundToastTick = it.txnNotFoundToastTick + 1)
                    }
                    return@launch
                }

                when (existingTxn.status) {
                    CountStatus.ON_HOLD -> {
                        _uiState.update { it.copy(isLoading = false, showOnHoldDialog = true) }
                        return@launch
                    }
                    CountStatus.PARTIAL -> {
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
                                rxNo = existingTxn.rxNo,
                                qty = existingTxn.targetCount?.toString(),
                                isHazardous = drug?.isHazardous ?: false,
                            )
                        }
                        logger.i("Auto-continuing txn=$txnId isNdcVerified=${existingTxn.isNdcVerified} → $targetStage")
                        return@launch
                    }
                    else -> { /* no other active status expected */ }
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
                            barcodeImagePath = imagePath ?: it.barcodeImagePath,
                            showNdcEquivalenceDialog = true,
                            isHazardous = drugInfo.isHazardous ?: false,
                        )
                    }
                    return@launch
                }

                // Hard mismatch: an expected NDC is set (from HL7 or from the
                // RX label) and the server didn't flag the scan as a
                // substitute. Surface a non-blocking toast and let the user
                // rescan — the popup-style dialog was too heavy for this case.
                if (!expectedNdc.isNullOrBlank() && drugInfo.ndc != expectedNdc) {
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
                        barcodeImagePath = imagePath ?: it.barcodeImagePath,
                        showNdcDetails = needsSheet,
                        isHazardous = drugInfo.isHazardous ?: false,
                    )
                }
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
            logger.i("RX confirmed, txn=$newTxnId, advancing to PRE_NDC")
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
            logger.i("NDC auto-confirmed (batch, no pre-txn), created txn=$newTxnId batchId=${state.batchId}")
            return
        }

        val txn = pillCountTxnDao.getById(txnId) ?: return
        val isSubstitute = state.isSubstituteConfirmed
        val substitutedDrugId = if (isSubstitute && state.ndcScannedValue.isNotBlank()) {
            drugMasterDao.upsertPreservingId(
                DrugMasterEntity(
                    ndc = state.ndcScannedValue,
                    drugName = state.ndcDrugName.ifBlank { state.drugName },
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
        logger.i("NDC auto-confirmed, txn=$txnId substitute=$isSubstitute, advancing to COUNTING")
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
            logger.i("Continuing txn=$txnId isNdcVerified=${txn.isNdcVerified} workflowStep=${txn.workflowStep} → $targetStage")
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
                    logger.i("Stock count SEALED confirmed, txn=$newTxnId batchId=${state.batchId}")
                } else {
                    _uiState.update {
                        it.copy(stage = DispenseStage.COUNTING, showNdcDetails = false, txnId = newTxnId)
                    }
                    logger.i("Stock count OPENED confirmed, txn=$newTxnId batchId=${state.batchId}, advancing to COUNTING")
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
            logger.i("NDC confirmed, txn=$txnId substitute=$isSubstitute, advancing to COUNTING")
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

    fun clearNavigateToBatch() {
        _uiState.update { it.copy(navigateToBatchId = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
