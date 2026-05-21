package com.rite.pillcounting.feature.dispenseScan.presentation.viewmodel

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
import com.rite.pillcounting.feature.barcodeScan.domain.data.IDrugRepository
import com.rite.pillcounting.feature.barcodeScan.domain.model.DrugInfo
import com.rite.pillcounting.feature.barcodeScan.domain.model.GetNdcRequestModel
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
 * ViewModel for the merged dispense flow ([com.rite.pillcounting.feature.dispenseScan.presentation.DispenseScanScreen]).
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
class DispenseScanViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val drugRepository: IDrugRepository,
    private val drugMasterDao: DrugMasterDao,
    private val preferenceHelper: PreferenceHelper,
    private val pillCountTxnDao: PillCountTxnDao,
) : ViewModel() {

    private val logger = AppLogger("DispenseScanVM")

    private val _uiState = MutableStateFlow(DispenseScanUiState())
    val uiState: StateFlow<DispenseScanUiState> = _uiState.asStateFlow()

    private var countType: CountType = CountType.FIXED

    fun setCountType(type: String) {
        countType = runCatching { CountType.valueOf(type) }.getOrDefault(CountType.FIXED)
        _uiState.update { it.copy(scanType = type) }
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
                )
            }
            logger.i("HL7 init: txn=$txnId drug=${drug?.drugName} expectedNdc=${drug?.ndc}")
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

                val localDrug = drugMasterDao.getDrugByNdc(parsedNdc)
                if (localDrug != null) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            drugName = localDrug.drugName ?: "Unknown Drug",
                            ndc = localDrug.ndc,
                            barcodeImagePath = imagePath,
                            rxNo = rxNo,
                            qty = qty,
                            showRxDetails = true,
                        )
                    }
                    return@launch
                }

                val drugInfo: DrugInfo? = drugRepository.getDrugInfoByNdc(
                    GetNdcRequestModel(target_ndc = "", scanned_ndc = parsedNdc)
                )
                if (drugInfo != null) {
                    val displayName = drugInfo.genericName?.takeIf { it.isNotBlank() }
                        ?: "Unknown Drug"
                    drugMasterDao.upsertPreservingId(
                        DrugMasterEntity(
                            ndc = drugInfo.ndc,
                            drugName = displayName,
                            drugType = drugInfo.drugType,
                            gtin = parsedNdc,
                            packageQty = drugInfo.qty,
                        )
                    )
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            drugName = displayName,
                            ndc = drugInfo.ndc,
                            barcodeImagePath = imagePath,
                            rxNo = rxNo,
                            qty = qty,
                            showRxDetails = true,
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(isLoading = false, showNdcNotFoundDialog = true)
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
     *   1. Local DB lookup. If [DispenseScanUiState.hl7ExpectedNdc] is set and
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
                val expectedNdc = _uiState.value.hl7ExpectedNdc

                // Try local DB first — but only trust a local hit when there's no
                // HL7 expected NDC, or when the local hit matches it exactly.
                // Otherwise fall through to the server so we can detect a
                // substitute (the server may resolve a different scanned GTIN
                // back to the expected NDC).
                val localDrug = drugMasterDao.getDrugByNdc(gtin14)
                val trustLocal = localDrug != null &&
                        (expectedNdc.isNullOrBlank() || expectedNdc == localDrug.ndc)

                if (trustLocal) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            ndcScannedValue = localDrug!!.ndc,
                            ndcDrugName = localDrug.drugName ?: it.drugName,
                            barcodeImagePath = imagePath ?: it.barcodeImagePath,
                            showNdcDetails = true,
                        )
                    }
                    return@launch
                }

                // Server lookup with HL7 context so substitutes get flagged.
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
                    )
                )

                val isSubstitute = drugInfo.is_ndc_equivalent == true
                if (isSubstitute) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            ndcScannedValue = drugInfo.ndc,
                            ndcDrugName = displayName,
                            barcodeImagePath = imagePath ?: it.barcodeImagePath,
                            showNdcEquivalenceDialog = true,
                        )
                    }
                    return@launch
                }

                // Hard mismatch: expected an HL7 NDC but server didn't even flag
                // an equivalence. Surface a non-blocking toast and let the user
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

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        ndcScannedValue = drugInfo.ndc,
                        ndcDrugName = displayName,
                        barcodeImagePath = imagePath ?: it.barcodeImagePath,
                        showNdcDetails = true,
                    )
                }
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
        _uiState.update {
            it.copy(
                showNdcEquivalenceDialog = false,
                isSubstituteConfirmed = true,
                showNdcDetails = true,
            )
        }
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

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

enum class DispenseStage { PRE_RX, PRE_NDC, COUNTING }

data class DispenseScanUiState(
    val stage: DispenseStage = DispenseStage.PRE_RX,
    val scanType: String = CountType.FIXED.toString(),

    val drugName: String = "",
    val ndc: String = "",
    val rxNo: String? = null,
    val qty: String? = null,
    val selectedBucketId: String = "",
    val barcodeImagePath: String? = null,

    val ndcScannedValue: String = "",
    val ndcDrugName: String = "",

    val selectedContainerStatus: ContainerStatus = ContainerStatus.SEALED,

    val showRxDetails: Boolean = false,
    val showNdcDetails: Boolean = false,
    val showInvalidScanDialog: Boolean = false,
    val showNdcNotFoundDialog: Boolean = false,
    val showNdcEquivalenceDialog: Boolean = false,

    // Transient toast signals — set briefly and cleared once the screen has
    // surfaced the toast. Unlike the dialogs above, these don't gate the
    // analyzer, so the user can rescan immediately.
    val scanNdcToastTick: Int = 0,
    val ndcMismatchToastTick: Int = 0,

    val isLoading: Boolean = false,
    val error: String? = null,

    // HL7/PMS-driven entry: when true the user landed here from a PMS
    // notification, the txn already exists, and the RX scan stage is skipped.
    val isFromHl7: Boolean = false,
    // Expected NDC from HL7 (= the drug PMS told us to dispense). Used to
    // detect substitute / mismatch when the user scans the container.
    val hl7ExpectedNdc: String? = null,
    val isSubstituteConfirmed: Boolean = false,

    val txnId: Long = 0L,
)
