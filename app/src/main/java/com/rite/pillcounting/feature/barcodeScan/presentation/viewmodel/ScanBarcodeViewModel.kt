package com.rite.pillcounting.feature.barcodeScan.presentation.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rite.pillcounting.core.room.dao.DrugMasterDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.models.DrugMasterEntity
import com.rite.pillcounting.core.room.models.PillCountTxnEntity
import com.rite.pillcounting.core.room.models.enums.CountStatus
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.barcodeScan.domain.data.IDrugRepository
import com.rite.pillcounting.feature.barcodeScan.domain.data.NavigationEvent
import com.rite.pillcounting.feature.barcodeScan.domain.data.ScanBarcodeEvent
import com.rite.pillcounting.feature.barcodeScan.domain.model.DrugInfo
import com.rite.pillcounting.feature.barcodeScan.domain.model.GetNdcRequestModel
import com.rite.pillcounting.feature.barcodeScan.domain.model.ScanBarcodeUiState
import com.rite.pillcounting.core.room.models.enums.ScanType
import com.rite.pillcounting.feature.barcodeScan.presentation.analyzer.BarcodeAnalyzer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import parseScanData
import javax.inject.Inject

/**
 * ViewModel responsible for handling all business logic of the Barcode Scanning screen.
 *
 * Responsibilities:
 * - Manage camera scanner state (start, stop, redo).
 * - Process scanned barcodes:
 *   - Lookup drug in local DB first.
 *   - If not found, fetch from API.
 * - Create pill count transactions and persist them in local Room DB.
 * - Expose navigation events to drive UI transitions.
 *
 * @property savedStateHandle Used to retrieve navigation arguments (e.g., scan type).
 * @property drugRepository Repository for fetching drug details from a remote source.
 * @property drugMasterDao DAO for managing drug master data.
 * @property preferenceHelper Wrapper for persisting local IDs and preferences.
 * @property pillCountTxnDao DAO for handling pill count transaction records.
 */
@HiltViewModel
class ScanBarcodeViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val drugRepository: IDrugRepository,
    private val drugMasterDao: DrugMasterDao,
    private val preferenceHelper: PreferenceHelper,
    private val pillCountTxnDao: PillCountTxnDao,
    val analyzer: BarcodeAnalyzer
) : ViewModel() {

    /** Logger instance scoped to this ViewModel for debugging and error tracking. */
    private val logger = AppLogger.create<ScanBarcodeViewModel>()

    /** Backing state for the UI layer (state hoisted for Compose). */
    private val _uiState = MutableStateFlow(ScanBarcodeUiState())

    /** Public immutable view of the UI state. */
    val uiState: StateFlow<ScanBarcodeUiState> = _uiState.asStateFlow()

    /** Backing channel for one-time navigation events (e.g., navigate to PillCount screen). */
    private val _navigationEvent = Channel<NavigationEvent>()

    /** Public Flow that UI can collect to observe navigation actions. */
    val navigationEvent = _navigationEvent.receiveAsFlow()

    /** Manual entry of drugname and ndc **/
    var drugName by mutableStateOf("")
    var ndc by mutableStateOf("")

    private val _drugInfo = MutableStateFlow<DrugInfo?>(null)
    val drugInfo: StateFlow<DrugInfo?> = _drugInfo

    private val _isSoundOverride =
        MutableStateFlow(preferenceHelper.isSoundOverride())

    val isSoundEnabled: StateFlow<Boolean> = _isSoundOverride.asStateFlow()

    private val _txnScanType = MutableStateFlow(ScanType.RX_LABEL)
    val txnScanType: StateFlow<ScanType> = _txnScanType

    fun setScanType(type: ScanType) {
        _txnScanType.value = type
    }

    init {
        val scanType = savedStateHandle.get<String>(ARG_TYPE) ?: ""
        _uiState.update { it.copy(scanType = scanType) }
        logger.i("ViewModel initialized with scanType: '$scanType'")
        loadExpectedNdcFromTxn()
    }

    /**
     * Central event dispatcher for UI-triggered or system-triggered events.
     *
     * @param event The event from [ScanBarcodeEvent] that needs to be handled.
     */
    fun onEvent(event: ScanBarcodeEvent) {
        logger.d("Received event: ${event::class.java.simpleName}")
        when (event) {
            /*is ScanBarcodeEvent.BarcodeScanned -> processBarcode(
                gtin14 = event.gtin14,
                imagePath = event.imagePath,
                expiry = event.expiry,
                lotNo = event.lotNo
            )*/

            is ScanBarcodeEvent.ScannerError -> handleScannerError(event.exception)
            is ScanBarcodeEvent.StartCount -> handleStartCount()
            ScanBarcodeEvent.RedoScan -> handleRedoScan()
            is ScanBarcodeEvent.ScanBarcode -> handleScanData(
                gtin14 = event.gtin14,
                imagePath = event.imagePath,
                expiry = event.expiry,
                lotNo = event.lotNo
            )

            is ScanBarcodeEvent.CreateTxn -> createTxn()
        }
    }

    private fun loadExpectedNdcFromTxn() {
        viewModelScope.launch {
            val txnId = preferenceHelper.getTxnId()
            val txn = pillCountTxnDao.getById(txnId) ?: return@launch

            if (txn.isComingFromHL7 == true) {
                val drugId = txn.drugId ?: return@launch
                val drug = drugMasterDao.getDrugById(drugId) ?: return@launch

                logger.i("Loaded expected NDC from DrugMaster: ${drug.ndc}")

                println("Loaded expected NDC from DrugMaster: ${drug.ndc}")

                _uiState.update {
                    it.copy(
                        hl7ExpectedNdc = drug.ndc,
                        drugName = drug.drugName ?: it.drugName
                    )
                }
            }
        }
    }


    /**
     * Process a scanned barcode value.
     *
     * - First checks if the drug exists in local DB ([DrugMasterDao]).
     * - If not found, fetches from remote API via [IDrugRepository].
     * - Updates [uiState] with result (drug name, NDC, error, etc.).
     *
     * @param gtin14 Raw string value from the scanned barcode.
     */
    /*private fun processBarcode(gtin14: String, imagePath: String, expiry: String, lotNo: String) {
        if (uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            try {
                val expectedHl7Ndc = uiState.value.hl7ExpectedNdc
                val request = GetNdcRequestModel(
                    target_ndc = expectedHl7Ndc ?: "",
                    scanned_ndc = gtin14
                )
                val drugInfo = drugRepository.getDrugInfoByNdc(request)
                if (drugInfo != null) {
                    _drugInfo.value = drugInfo
                    val displayName =
                        drugInfo.genericName?.takeIf { it.isNotBlank() } ?: "Unknown Drug"
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            drugName = displayName,
                            ndc = drugInfo.ndc,
                            barcodeImagePath = imagePath,
                            isScannerActive = false,
                            expiry = expiry,
                            lotNo = lotNo,
                            showNdcEquivalenceDialog = drugInfo.is_ndc_equivalent == true
                        )
                    }

                    if (drugInfo.is_ndc_equivalent == false) {
                        onEvent(ScanBarcodeEvent.StartCount())
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            showNdcNotFoundDialog = true
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to find drug information.",
                    )
                }
            }
        }
    }*/

    private fun handleScanData(gtin14: String, imagePath: String, expiry: String, lotNo: String) {
        if (uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            try {
                val expectedHl7Ndc = uiState.value.hl7ExpectedNdc

                val parsedNdc: String?
                val qty: String?
                val rxNo: String?

                if (_txnScanType.value == ScanType.RX_LABEL) {
                    val result = parseScanData(
                        preferenceHelper.getBarcodeRegex().toString(),
                        gtin14
                    )
                    parsedNdc = result.ndcNo
                    qty = result.qty
                    rxNo = result.rxNo
                } else {
                    // BARCODE → no parsing
                    parsedNdc = gtin14
                    qty = null
                    rxNo = null
                }

                if (_txnScanType.value == ScanType.BARCODE && gtin14.contains(
                        "RX",
                        ignoreCase = true
                    )
                ) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            showInvalidScanDialog = true
                        )
                    }
                    return@launch
                } else if (_txnScanType.value == ScanType.RX_LABEL) {

                    val isValid = !parsedNdc.isNullOrBlank() &&
                            !qty.isNullOrBlank() &&
                            !rxNo.isNullOrBlank()

                    if (!isValid) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                showInvalidScanDialog = true
                            )
                        }
                        return@launch
                    }
                }

                val scannedLookupValue = if (_txnScanType.value == ScanType.RX_LABEL) {
                    parsedNdc.orEmpty()
                } else {
                    gtin14
                }

                if (scannedLookupValue.isBlank()) {
                    logger.w("Scanned lookup value is blank. scanType=${_txnScanType.value}")
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            showNdcNotFoundDialog = true
                        )
                    }
                    return@launch
                }

                logger.i(
                    "ScanType=${_txnScanType.value}, expectedHl7Ndc='$expectedHl7Ndc', scannedLookupValue='$scannedLookupValue'"
                )

                // Local search:
                // - if HL7 expected NDC exists, search local using expected HL7 NDC
                // - else search local using scanned value
                val drug = if (expectedHl7Ndc.isNullOrEmpty()) {

                    logger.w("No expected HL7 NDC, using scanned value for local lookup.")
                    drugMasterDao.getDrugByNdc(scannedLookupValue)

                } else {

                    logger.i("Expected HL7 NDC: '$expectedHl7Ndc', Scanned: '$scannedLookupValue'")

                    if (expectedHl7Ndc == scannedLookupValue) {
                        logger.i("NDC matched → checking local DB")
                        drugMasterDao.getDrugByNdc(expectedHl7Ndc)
                    } else {
                        logger.w("NDC mismatch → skipping local, will fetch from server")
                        null
                    }
                }


                if (drug != null) {
                    logger.i("Drug exists locally: ${drug.drugName}")

                    val isEquivalent = drug.equivalence.equals("true", ignoreCase = true)

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            drugName = drug.drugName ?: "Unknown Drug",
                            ndc = drug.ndc ?: "",
                            barcodeImagePath = imagePath,
                            isScannerActive = false,
                            expiry = expiry,
                            lotNo = lotNo,
                            showNdcEquivalenceDialog = isEquivalent,
                            rxNo = rxNo ?: "",
                            qty = qty ?: "",
                            showScanSuccessfullyDialog = !isEquivalent
                        )
                    }
                } else {
                    logger.i("Drug not found locally. Fetching from server.")

                    val request = GetNdcRequestModel(
                        target_ndc = expectedHl7Ndc ?: "",
                        scanned_ndc = scannedLookupValue
                    )

                    val drugInfo = drugRepository.getDrugInfoByNdc(request)

                    if (drugInfo != null) {
                        _drugInfo.value = drugInfo

                        val displayName =
                            drugInfo.genericName?.takeIf { it.isNotBlank() } ?: "Unknown Drug"

                        drugMasterDao.upsertPreservingId(
                            DrugMasterEntity(
                                ndc = drugInfo.ndc,
                                drugName = displayName,
                                drugType = drugInfo.drugType,
                                equivalence = drugInfo.is_ndc_equivalent.toString()
                            )
                        )

                        _uiState.update {
                            val isEquivalent = drugInfo.is_ndc_equivalent == true

                            it.copy(
                                isLoading = false,
                                drugName = displayName,
                                ndc = drugInfo.ndc,
                                barcodeImagePath = imagePath,
                                isScannerActive = false,
                                expiry = expiry,
                                lotNo = lotNo,
                                rxNo = rxNo ?: "",
                                qty = qty ?: "",
                                showNdcEquivalenceDialog = isEquivalent,
                                showScanSuccessfullyDialog = !isEquivalent
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                showNdcNotFoundDialog = true
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to find drug information.",
                    )
                }
            }
        }
    }


    /**
     * Reset the UI state for a redo scan.
     *
     * Clears drug info and error messages, and re-activates the scanner.
     */
    private fun handleRedoScan() {
        logger.d("Redo scan triggered → resuming scanner.")
        analyzer.resume()
        _uiState.update {
            it.copy(
                drugName = "",
                ndc = "",
                error = null,
                isScannerActive = true
            )
        }
    }

    fun hideNdcNotMatchedDialog() {
        analyzer.resume()
        _uiState.update {
            it.copy(
                showNdcEquivalenceDialog = false,
                showNdcNotFoundDialog = false,
                showInvalidScanDialog = false
            )
        }
    }

    /**
     * Handle confirmation of a scanned drug and create a pill count transaction.
     *
     * - Validates that a scanned NDC exists.
     * - Uses [DrugMasterDao.upsertPreservingId] to ensure the drug is persisted.
     * - Creates a [PillCountTxnEntity] and persists it in [PillCountTxnDao].
     * - Emits a navigation event to proceed to pill count screen.
     */
    private fun handleStartCount() {

        val currentNdc = uiState.value.ndc
        if (currentNdc.isBlank()) return

        viewModelScope.launch {

            val txnId = preferenceHelper.getTxnId()
            if (txnId.toString() == "0") {
                val drugId = drugMasterDao.upsertPreservingId(
                    DrugMasterEntity(
                        ndc = currentNdc,
                        drugName = uiState.value.drugName
                    )
                )
                val qtyInt = uiState.value.qty?.toIntOrNull() ?: 0
                val txn = PillCountTxnEntity(
                    localId = preferenceHelper.getLocalId(),
                    drugId = drugId,
                    countType = CountType.valueOf(uiState.value.scanType),
                    status = CountStatus.PARTIAL,
                    expiry = uiState.value.expiry,
                    lotNo = uiState.value.lotNo,
                    barcodeImage = uiState.value.barcodeImagePath,
                    isNdcVerified = true,
                    targetCount = qtyInt
                )

                val newTxnId = pillCountTxnDao.upsertPreservingId(txn)
                preferenceHelper.saveTxnId(newTxnId)
            } else {
                val txn = pillCountTxnDao.getById(txnId) ?: return@launch
                val drugId = drugMasterDao.upsertPreservingId(
                    DrugMasterEntity(
                        ndc = currentNdc,
                        drugName = uiState.value.drugName
                    )
                )
                pillCountTxnDao.update(
                    txn.copy(
                        countType = CountType.valueOf(uiState.value.scanType),
                        status = CountStatus.PARTIAL,
                        expiry = uiState.value.expiry,
                        barcodeImage = uiState.value.barcodeImagePath,
                        isNdcVerified = true,
                        drugId = drugId
                    )
                )
                logger.i("HL7 txn updated with scan data txnId=$txnId")

            }
            if (_txnScanType.value == ScanType.BARCODE) {
                _navigationEvent.send(
                    NavigationEvent.NavigateToPillCount(
                        ndc = currentNdc,
                        type = uiState.value.scanType
                    )
                )
            }
        }
    }

    private fun createTxn() {

        _uiState.update {
            it.copy(
                showNdcEquivalenceDialog = false
            )
        }

        val currentNdc = uiState.value.ndc
        if (currentNdc.isBlank()) return

        viewModelScope.launch {

            val txnId = preferenceHelper.getTxnId()
            if (txnId.toString() == "0") {
                val drugId = drugMasterDao.upsertPreservingId(
                    DrugMasterEntity(
                        ndc = currentNdc,
                        drugName = uiState.value.drugName
                    )
                )
                val qtyInt = uiState.value.qty?.toIntOrNull() ?: 0
                val txn = PillCountTxnEntity(
                    localId = preferenceHelper.getLocalId(),
                    drugId = drugId,
                    countType = CountType.valueOf(uiState.value.scanType),
                    status = CountStatus.PARTIAL,
                    expiry = uiState.value.expiry,
                    lotNo = uiState.value.lotNo,
                    barcodeImage = uiState.value.barcodeImagePath,
                    isNdcVerified = false,
                    targetCount = qtyInt
                )

                val newTxnId = pillCountTxnDao.upsertPreservingId(txn)
                preferenceHelper.saveTxnId(newTxnId)
            }
            if (_txnScanType.value == ScanType.BARCODE) {
                _navigationEvent.send(
                    NavigationEvent.NavigateToPillCount(
                        ndc = currentNdc,
                        type = uiState.value.scanType
                    )
                )
            }
            if (_txnScanType.value == ScanType.RX_LABEL) {
                setScanType(ScanType.BARCODE)
            }
        }
    }


    /**
     * Handle scanner errors reported from MLKit or CameraX.
     *
     * @param exception The thrown exception.
     */
    private fun handleScannerError(exception: Exception) {
        logger.e("Scanner error received", exception)
        _uiState.update { it.copy(error = "Scanner failed. Please try again.") }
    }


    fun showSuccessDialog() {
        _uiState.update {
            it.copy(
                showScanSuccessfullyDialog = true
            )
        }
    }

    fun hideSuccessDialog() {
        _uiState.update {
            it.copy(
                showScanSuccessfullyDialog = false
            )
        }
    }

    fun clearToast() {
        _uiState.update {
            it.copy(
                error = null
            )
        }
    }

    companion object {
        /** Navigation argument key for scan type ("FIXED" or "REGULAR"). */
        const val ARG_TYPE = "type"
    }
}
