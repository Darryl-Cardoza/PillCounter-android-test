package com.rite.pillcounting.feature.barcodeScan.presentation.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rite.pillcounting.core.room.dao.BatchDao
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
import com.rite.pillcounting.core.utils.compose.ContainerStatus
import com.rite.pillcounting.feature.barcodeScan.presentation.analyzer.BarcodeAnalyzer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ParsedScanData
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
 * - Create batch entries when user confirms "Create New Batch" dialog.
 * - Expose navigation events to drive UI transitions.
 *
 * @property savedStateHandle Used to retrieve navigation arguments (e.g., scan type).
 * @property drugRepository Repository for fetching drug details from a remote source.
 * @property drugMasterDao DAO for managing drug master data.
 * @property batchDao DAO for managing batch data.
 * @property preferenceHelper Wrapper for persisting local IDs and preferences.
 * @property pillCountTxnDao DAO for handling pill count transaction records.
 * @property analyzer MLKit barcode scanner analyzer.
 */
@HiltViewModel
class ScanBarcodeViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val drugRepository: IDrugRepository,
    private val drugMasterDao: DrugMasterDao,
    private val batchDao: BatchDao,
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

    fun getBucketList(): List<String> = preferenceHelper.getBucketList()

    fun setBatchId(batchId: Long) {
        _uiState.update { it.copy(batchId = batchId) }
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
            is ScanBarcodeEvent.OnContainerStatusChanged -> onContainerStatusChanged(event.status)
            is ScanBarcodeEvent.OnBucketSelected -> _uiState.update { it.copy(selectedBucketId = event.bucketId) }
            is ScanBarcodeEvent.InvalidScan -> {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        showInvalidScanDialog = true
                    )
                }
            }
//            ScanBarcodeEvent.CreateBatchId -> createBatch()
        }
    }


    fun onContainerStatusChanged(status: ContainerStatus) {
        _uiState.update {
            it.copy(
                selectedContainerStatus = status
            )
        }
    }

    private fun loadExpectedNdcFromTxn() {
        viewModelScope.launch {
            val txnId = preferenceHelper.getTxnId()
            val txn = pillCountTxnDao.getById(txnId) ?: return@launch

            if(txn.countType == CountType.FIXED){
                val drugId = txn.drugId ?: return@launch
                val drug = drugMasterDao.getDrugById(drugId) ?: return@launch

                logger.i("Loaded expected NDC from DrugMaster: ${drug.ndc}")


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
     * Extract and parse scan data based on scan type.
     *
     * For RX_LABEL scans the raw value is parsed against the configured regex template
     * ({RXNO}|{NDCNO}|{QTY}|{BUCKET}), extracting all four fields.
     * For STOCK_COUNT and BARCODE scans the raw gtin14 value is used as-is for the NDC;
     * all other fields remain null.
     *
     * @param currentScanType The type of scan being performed.
     * @param gtin14 The raw scanned value.
     * @return [ParsedScanData] containing ndcNo, qty, rxNo, and bucket.
     */
    private fun parseScanMetadata(
        currentScanType: ScanType,
        gtin14: String
    ): ParsedScanData {
        return if (currentScanType == ScanType.RX_LABEL) {
            parseScanData(
                preferenceHelper.getBarcodeRegex().toString(),
                gtin14
            )
        } else {
            // STOCK_COUNT / BARCODE — use gtin14 directly as NDC, no other fields
            ParsedScanData(ndcNo = gtin14)
        }
    }

    /**
     * Validate the parsed scan data.
     *
     * @param currentScanType The type of scan being performed.
     * @param gtin14 The raw scanned value.
     * @param parsedNdc The parsed NDC (may be null).
     * @param qty The parsed quantity (may be null).
     * @param rxNo The parsed RX number (may be null).
     * @return true if scan data is valid, false otherwise.
     */
    private fun isScanDataValid(
        currentScanType: ScanType,
        gtin14: String,
        parsedNdc: String?,
        qty: String?,
        rxNo: String?
    ): Boolean {
        val isInvalidBarcodeScan =
            currentScanType == ScanType.BARCODE && gtin14.contains("RX", ignoreCase = true)
        if (isInvalidBarcodeScan) return false

        val isInvalidRxLabelScan =
            (currentScanType == ScanType.RX_LABEL) &&
                    (parsedNdc.isNullOrBlank() || qty.isNullOrBlank() || rxNo.isNullOrBlank())

        val isInvalidStockCountScan =
            (currentScanType == ScanType.STOCK_COUNT) && parsedNdc.isNullOrBlank()

        return !isInvalidRxLabelScan && !isInvalidStockCountScan
    }

    /**
     * Determine the scanned lookup value based on scan type.
     *
     * @param currentScanType The type of scan.
     * @param parsedNdc The parsed NDC.
     * @param gtin14 The raw GTIN-14 value.
     * @return The value to use for NDC lookup.
     */
    private fun getScannedLookupValue(
        currentScanType: ScanType,
        parsedNdc: String?,
        gtin14: String
    ): String {
        return when (currentScanType) {
            ScanType.RX_LABEL -> parsedNdc.orEmpty()
            ScanType.STOCK_COUNT -> parsedNdc.orEmpty() // parsedNdc contains gtin for STOCK_COUNT
            else -> gtin14
        }
    }

    /**
     * Lookup drug from local database.
     *
     * @param expectedHl7Ndc Expected NDC from HL7, if any.
     * @param scannedLookupValue The NDC value to search for.
     * @return Drug entity if found, null otherwise.
     */
    private suspend fun lookupLocalDrug(
        expectedHl7Ndc: String?,
        scannedLookupValue: String,
        currentScanType: ScanType
    ): DrugMasterEntity? {
        return if (currentScanType == ScanType.STOCK_COUNT) {
            logger.i("Stock count flow -> checking local DrugMaster by GTIN: '$scannedLookupValue'")
            drugMasterDao.getDrugByGtin(scannedLookupValue)
        } else {
            when {
                expectedHl7Ndc.isNullOrEmpty() -> {
                    logger.w("No expected HL7 NDC found, checking local DrugMaster using scanned value: '$scannedLookupValue'")
                    drugMasterDao.getDrugByNdc(scannedLookupValue)
                }

                expectedHl7Ndc == scannedLookupValue -> {
                    logger.i("NDC matched -> checking local DrugMaster by NDC: '$expectedHl7Ndc'")
                    drugMasterDao.getDrugByNdc(expectedHl7Ndc)
                }

                else -> {
                    logger.w(
                        "NDC mismatch (HL7: '$expectedHl7Ndc', Scanned: '$scannedLookupValue') -> skipping local lookup and falling back to server"
                    )
                    null
                }
            }
        }
    }

    /**
     * Checks if a PMS batch scan is valid.
     *
     * When the batch has a non-null [requestIdFromPMS] (i.e., coming from PMS), the scanned drug/lot/expiry combination
     * must match a pre-loaded PMS transaction in the batch.
     *
     * Matching rules (null-tolerant):
     * - If the PMS txn has no lot → any scanned lot is accepted (NDC-only match)
     * - If the PMS txn has no expiry → any scanned expiry is accepted
     * - If PMS txn has lot AND expiry → both must match exactly
     *
     * Returns true (allow) when:
     * - batchId is 0 (not in a batch)
     * - batch not found or not from PMS
     * - a matching PMS txn exists
     *
     * @param batchId Current batch ID (0 means no batch).
     * @param drugId Drug ID resolved from local/server lookup.
     * @param lotNo Lot number from the scan (null if blank).
     * @param expiry Expiry from the scan (null if blank).
     * @return true if allowed to proceed, false if the NDC doesn't match any PMS txn.
     */
    private suspend fun isPmsBatchScanValid(
        batchId: Long,
        drugId: Long,
        lotNo: String?,
        expiry: String?
    ): Boolean {
        if (batchId == 0L) return true
        val batch = batchDao.getById(batchId) ?: return true
        if (batch.requestIdFromPMS == null) return true
        val match = pillCountTxnDao.findPmsTxnInBatch(
            batchId = batchId,
            drugId = drugId.toString()
        )
        return match != null
    }

    /**
     * Build common drug result state updates.
     *
     * @param drugName The drug name.
     * @param ndcValue The NDC value.
     * @param imagePath The barcode image path.
     * @param expiry The expiry date.
     * @param lotNo The lot number.
     * @param rxNo The RX number.
     * @param qty The quantity.
     * @param isEquivalent Whether drug is equivalent.
     * @return Updated UI state.
     */
    private fun buildDrugResultState(
        drugName: String,
        ndcValue: String,
        imagePath: String,
        expiry: String,
        lotNo: String,
        rxNo: String?,
        qty: String?,
        isEquivalent: Boolean
    ): ScanBarcodeUiState.() -> ScanBarcodeUiState = {
        this.copy(
            isLoading = false,
            drugName = drugName,
            ndc = ndcValue,
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

    /**
     * Process scanned barcode by looking up locally first, then remotely if needed.
     *
     * @param gtin14 Raw scanned barcode value.
     * @param imagePath Path to barcode image.
     * @param expiry Expiry date from scan.
     * @param lotNo Lot number from scan.
     */
    private fun handleScanData(
        gtin14: String,
        imagePath: String,
        expiry: String,
        lotNo: String
    ) {
        if (uiState.value.isLoading) return

        _uiState.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            try {
                val currentScanType = _txnScanType.value
                val expectedHl7Ndc = uiState.value.hl7ExpectedNdc

                // Parse scan metadata based on scan type
                val scanMeta = parseScanMetadata(currentScanType, gtin14)
                val parsedNdc = scanMeta.ndcNo
                val qty       = scanMeta.qty
                val rxNo      = scanMeta.rxNo
                val bucket    = scanMeta.bucket

                // Auto-apply bucket from scan if present
                if (!bucket.isNullOrBlank()) {
                    _uiState.update { it.copy(selectedBucketId = bucket) }
                }

                // Validate scan data
                if (!isScanDataValid(currentScanType, gtin14, parsedNdc, qty, rxNo)) {
                    _uiState.update {
                        it.copy(isLoading = false, showInvalidScanDialog = true)
                    }
                    return@launch
                }

                // Determine lookup value
                val scannedLookupValue = getScannedLookupValue(currentScanType, parsedNdc, gtin14)

                if (scannedLookupValue.isBlank()) {
                    logger.w("Scanned lookup value is blank. scanType=$currentScanType")
                    _uiState.update {
                        it.copy(isLoading = false, showNdcNotFoundDialog = true)
                    }
                    return@launch
                }

                logger.i("Processing scan - Type: $currentScanType, HL7 NDC: '$expectedHl7Ndc', Scanned: '$scannedLookupValue'")

                // Try local lookup first
                val drug = lookupLocalDrug(expectedHl7Ndc, scannedLookupValue, currentScanType)

                if (drug != null) {
                    logger.i("Drug found locally: ${drug.drugName}")

                    val lotNoNullable = lotNo.ifBlank { null }
                    val expiryNullable = expiry.ifBlank { null }
                    if (!isPmsBatchScanValid(uiState.value.batchId, drug.drugId, lotNoNullable, expiryNullable)) {
                        logger.w("PMS batch mismatch for drugId=${drug.drugId}, lot=$lotNoNullable, expiry=$expiryNullable")
                        _uiState.update { it.copy(isLoading = false, isScannerActive = false, showPmsNdcMismatchDialog = true) }
                        return@launch
                    }

                    val isEquivalent = false
                    val packageQty = if (currentScanType == ScanType.STOCK_COUNT) {
                        drug.packageQty
                    } else {
                        qty
                    }
                    _uiState.update(
                        buildDrugResultState(
                            drugName = drug.drugName ?: "Unknown Drug",
                            ndcValue = drug.ndc ?: "",
                            imagePath = imagePath,
                            expiry = expiry,
                            lotNo = lotNo,
                            rxNo = rxNo,
                            qty = packageQty.toString(),
                            isEquivalent = isEquivalent
                        )
                    )
                    return@launch
                }

                // Drug not found locally - fetch from server
                logger.i("Drug not found locally, fetching from server")
                processDrugFromServer(
                    expectedHl7Ndc, scannedLookupValue, imagePath,
                    expiry, lotNo, rxNo, qty, currentScanType
                )
            } catch (e: Exception) {
                logger.e("Error processing scan", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to find drug information."
                    )
                }
            }
        }
    }

    /**
     * Fetch drug information from remote server and update state.
     *
     * @param expectedHl7Ndc Expected NDC from HL7.
     * @param scannedLookupValue The scanned NDC value.
     * @param imagePath Path to barcode image.
     * @param expiry Expiry date.
     * @param lotNo Lot number.
     * @param rxNo RX number (may be null).
     * @param qty Quantity (may be null).
     */
    private suspend fun processDrugFromServer(
        expectedHl7Ndc: String?,
        scannedLookupValue: String,
        imagePath: String,
        expiry: String,
        lotNo: String,
        rxNo: String?,
        qty: String?,
        currentScanType: ScanType
    ) {
        val request = GetNdcRequestModel(
            target_ndc = expectedHl7Ndc ?: "",
            scanned_ndc = scannedLookupValue
        )

        val drugInfo = drugRepository.getDrugInfoByNdc(request)

        if (drugInfo != null) {
            _drugInfo.value = drugInfo

            val displayName = drugInfo.genericName?.takeIf { it.isNotBlank() } ?: "Unknown Drug"
            val qty = if (currentScanType == ScanType.STOCK_COUNT) {
                drugInfo.qty
            } else {
                qty
            }
            // Persist to local DB
            drugMasterDao.upsertPreservingId(
                DrugMasterEntity(
                    ndc = drugInfo.ndc,
                    drugName = displayName,
                    drugType = drugInfo.drugType,
                    gtin = scannedLookupValue,
                    packageQty = drugInfo.qty,
                    isHazardous = drugInfo.isHazardous ?: false,
                )
            )

            val isEquivalent = drugInfo.is_ndc_equivalent == true
            logger.i("Drug fetched from server: $displayName (equivalent: $isEquivalent)")

            val resolvedDrugId = drugMasterDao.getDrugIdByNdc(drugInfo.ndc) ?: 0L
            val lotNoNullable = lotNo.ifBlank { null }
            val expiryNullable = expiry.ifBlank { null }
            if (!isPmsBatchScanValid(uiState.value.batchId, resolvedDrugId, lotNoNullable, expiryNullable)) {
                logger.w("PMS batch mismatch for drugId=$resolvedDrugId, lot=$lotNoNullable, expiry=$expiryNullable")
                _uiState.update { it.copy(isLoading = false, isScannerActive = false, showPmsNdcMismatchDialog = true) }
                return
            }

            _uiState.update(
                buildDrugResultState(
                    drugName = displayName,
                    ndcValue = drugInfo.ndc,
                    imagePath = imagePath,
                    expiry = expiry,
                    lotNo = lotNo,
                    rxNo = rxNo,
                    qty = qty.toString(),
                    isEquivalent = isEquivalent
                )
            )
        } else {
            logger.w("Drug not found from server for NDC: $scannedLookupValue")
            _uiState.update {
                it.copy(isLoading = false, showNdcNotFoundDialog = true)
            }
        }
    }

    fun markSubstituteConfirmed() {
        _uiState.update { it.copy(isSubstituteConfirmed = true) }
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
                isScannerActive = true,
                isSubstituteConfirmed = false
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

    fun hidePmsMismatchDialog() {
        analyzer.resume()
        _uiState.update { it.copy(showPmsNdcMismatchDialog = false) }
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
            val isSubstitute = uiState.value.isSubstituteConfirmed
            val hl7Ndc = uiState.value.hl7ExpectedNdc

            val txnId = preferenceHelper.getTxnId()
            if (txnId.toString() == "0") {
                val drugId: Long
                val substitutedDrugId: Long?

                if (isSubstitute && !hl7Ndc.isNullOrBlank()) {
                    // drugId = original expected drug; substitutedDrugId = scanned substitute
                    drugId = drugMasterDao.upsertPreservingId(
                        DrugMasterEntity(ndc = hl7Ndc)
                    )
                    substitutedDrugId = drugMasterDao.upsertPreservingId(
                        DrugMasterEntity(ndc = currentNdc, drugName = uiState.value.drugName)
                    )
                } else {
                    drugId = drugMasterDao.upsertPreservingId(
                        DrugMasterEntity(ndc = currentNdc, drugName = uiState.value.drugName)
                    )
                    substitutedDrugId = null
                }

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
                    targetCount = qtyInt,
                    isSubstitute = isSubstitute,
                    substitutedDrugId = substitutedDrugId
                )

                val newTxnId = pillCountTxnDao.upsertPreservingId(txn)
                preferenceHelper.saveTxnId(newTxnId)
            } else {
                val txn = pillCountTxnDao.getById(txnId) ?: return@launch
                val substitutedDrugId = if (isSubstitute) {
                    drugMasterDao.upsertPreservingId(
                        DrugMasterEntity(ndc = currentNdc, drugName = uiState.value.drugName)
                    )
                } else {
                    null
                }
                pillCountTxnDao.update(
                    txn.copy(
                        countType = CountType.valueOf(uiState.value.scanType),
                        status = CountStatus.PARTIAL,
                        expiry = uiState.value.expiry,
                        barcodeImage = uiState.value.barcodeImagePath,
                        isNdcVerified = true,
                        drugId = txn.drugId,
                        isSubstitute = isSubstitute,
                        substitutedDrugId = substitutedDrugId
                    )
                )
                logger.i("HL7 txn updated with scan data txnId=$txnId, isSubstitute=$isSubstitute")
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
        _uiState.update { it.copy(showNdcEquivalenceDialog = false) }

        val currentNdc = uiState.value.ndc
        if (currentNdc.isBlank()) return

        if (_txnScanType.value == ScanType.STOCK_COUNT) {
            createSealedStockTxn()
            return
        }

        viewModelScope.launch {
            val isSubstitute = uiState.value.isSubstituteConfirmed
            val hl7Ndc = uiState.value.hl7ExpectedNdc

            val txnId = preferenceHelper.getTxnId()
            if (txnId.toString() == "0") {
                val drugId: Long?
                val substitutedDrugId: Long?

                if (isSubstitute && !hl7Ndc.isNullOrBlank()) {
                    drugId = drugMasterDao.getDrugIdByNdc(hl7Ndc)
                    substitutedDrugId = drugMasterDao.getDrugIdByNdc(currentNdc)
                } else {
                    drugId = drugMasterDao.getDrugIdByNdc(currentNdc)
                    substitutedDrugId = null
                }

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
                    targetCount = qtyInt,
                    bucketId = uiState.value.selectedBucketId.ifBlank { null },
                    rxNo = uiState.value.rxNo?.ifBlank { null },
                    isSubstitute = isSubstitute,
                    substitutedDrugId = substitutedDrugId
                )
                val newTxnId = pillCountTxnDao.upsertPreservingId(txn)
                preferenceHelper.saveTxnId(newTxnId)
                _uiState.update {
                    it.copy(
                        hl7ExpectedNdc = currentNdc,
                        drugName = uiState.value.drugName
                    )
                }
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
     * STOCK_COUNT + SEALED: create or merge a COMPLETED transaction under the current batch.
     * If a transaction for the same batch/drug/lotNo/expiry already exists, add the qty.
     */
    private fun createSealedStockTxn() {
        viewModelScope.launch {
            try {
                val state = uiState.value
                val batchIdLong = state.batchId

                val drugId = drugMasterDao.getDrugIdByNdc(ndc = state.ndc)

                val lotNo = state.lotNo.ifBlank { null }
                val expiry = state.expiry.ifBlank { null }

                val existingTxn = pillCountTxnDao.findSealedTxnInBatch(
                    batchId = batchIdLong,
                    drugId = drugId.toString(),
                    lotNo = lotNo,
                    expiry = expiry
                )

                if (existingTxn != null) {
                    if(_uiState.value.selectedContainerStatus== ContainerStatus.SEALED){
                        val mergedBottleQty = (existingTxn.bottleQty ?: 0) + 1
                        pillCountTxnDao.update(
                            existingTxn.copy(
                                bottleQty = mergedBottleQty,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                        logger.i("Merged sealed stock txn ${existingTxn.txnId}, totalQty=$mergedBottleQty")
                    }
                    preferenceHelper.saveTxnId(existingTxn.txnId)
                } else {
                    val bottleQty = if (_uiState.value.selectedContainerStatus == ContainerStatus.SEALED) {
                        1
                    } else {
                        0
                    }
                    val txn = PillCountTxnEntity(
                        localId = preferenceHelper.getLocalId(),
                        drugId = drugId,
                        countType = CountType.valueOf(state.scanType),
                        status = CountStatus.COMPLETED,
                        expiry = expiry,
                        lotNo = lotNo,
                        barcodeImage = state.barcodeImagePath,
                        bottleQty = bottleQty,
                        batchId = batchIdLong,
                        bucketId = state.selectedBucketId.ifBlank { null }
                    )
                    val newTxnId = pillCountTxnDao.upsertPreservingId(txn)
                    preferenceHelper.saveTxnId(newTxnId)
                    logger.i("Created sealed stock txn id=$newTxnId")
                }

                hideSuccessDialog()
                if (uiState.value.selectedContainerStatus == ContainerStatus.SEALED) {
                    _navigationEvent.send(NavigationEvent.NavigateToBatch(batchId = batchIdLong ?: 0))
                } else {
                    _navigationEvent.send(
                        NavigationEvent.NavigateToPillCount(
                            ndc = state.ndc,
                            type = state.scanType
                        )
                    )
                }
            } catch (e: Exception) {
                logger.e("Failed to save sealed stock txn", e)
                _uiState.update { it.copy(error = e.message ?: "Failed to save transaction") }
            }
        }
    }

    /**
     * STOCK_COUNT + OPENED: create a PARTIAL transaction under the current batch,
     * then navigate to PillCount screen for manual counting.
     */
    private fun createOpenedStockTxn() {
        viewModelScope.launch {
            try {
                val state = uiState.value
                val qtyInt = state.qty?.toIntOrNull() ?: 0
                val batchIdInt = state.batchId

                val drugId = drugMasterDao.upsertPreservingId(
                    DrugMasterEntity(ndc = state.ndc, drugName = state.drugName)
                )

                val txn = PillCountTxnEntity(
                    localId = preferenceHelper.getLocalId(),
                    drugId = drugId,
                    countType = CountType.valueOf(state.scanType),
                    status = CountStatus.PARTIAL,
                    expiry = state.expiry.ifBlank { null },
                    lotNo = state.lotNo.ifBlank { null },
                    barcodeImage = state.barcodeImagePath,
                    targetCount = qtyInt,
                    batchId = batchIdInt,
                    bucketId = state.selectedBucketId.ifBlank { null }
                )

                val newTxnId = pillCountTxnDao.upsertPreservingId(txn)
                preferenceHelper.saveTxnId(newTxnId)
                logger.i("Created opened stock txn id=$newTxnId, navigating to pill count")

                hideSuccessDialog()
                _navigationEvent.send(
                    NavigationEvent.NavigateToPillCount(
                        ndc = state.ndc,
                        type = state.scanType
                    )
                )
            } catch (e: Exception) {
                logger.e("Failed to save opened stock txn", e)
                _uiState.update { it.copy(error = e.message ?: "Failed to save transaction") }
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
