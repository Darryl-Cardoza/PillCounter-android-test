package com.dispensesure.retail.feature.dispense.presentation.viewmodel

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dispensesure.retail.R
import com.dispensesure.retail.core.analytics.AnalyticsService
import com.dispensesure.retail.core.audit.AuditLogger
import com.dispensesure.retail.core.events.DomainEvent
import com.dispensesure.retail.core.events.DomainEventBus
import com.dispensesure.retail.core.audit.model.DispenseResumed
import com.dispensesure.retail.core.audit.model.DispenseTxnCreated
import com.dispensesure.retail.core.audit.model.ResumeSource
import com.dispensesure.retail.core.audit.model.RxScanned
import com.dispensesure.retail.core.audit.model.SubstituteAccepted
import com.dispensesure.retail.core.audit.model.TxnSource
import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.room.models.DispenseTxnEntity
import com.dispensesure.retail.core.room.models.DrugMasterEntity
import com.dispensesure.retail.core.scanning.domain.model.DispenseBottle
import com.dispensesure.retail.core.tourguide.FtueController
import com.dispensesure.retail.core.tourguide.TutorialData
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.logger.LogEvent
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import com.dispensesure.retail.feature.dashboard.domain.model.KpiFilter
import com.dispensesure.retail.feature.dispense.data.ContainerOutcome
import com.dispensesure.retail.feature.dispense.data.ContainerVerifier
import com.dispensesure.retail.feature.dispense.data.DispenseFlowRepository
import com.dispensesure.retail.feature.dispense.data.DispenseStepResolver
import com.dispensesure.retail.feature.dispense.data.RxLabelResolver
import com.dispensesure.retail.feature.dispense.data.RxScanOutcome
import com.dispensesure.retail.feature.dispense.domain.model.DispenseFlowAction
import com.dispensesure.retail.feature.dispense.domain.model.DispenseFlowEffect
import com.dispensesure.retail.feature.dispense.domain.model.DispenseFlowUiState
import com.dispensesure.retail.feature.dispense.domain.model.DispenseStage
import com.dispensesure.retail.feature.dispense.domain.model.StandaloneRxDraft
import com.dispensesure.retail.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the merged dispense flow ([com.dispensesure.retail.feature.dispense.presentation.DispenseFlowScreen]).
 *
 * Three stages:
 *  - [DispenseStage.PRE_RX]   : no transaction yet, RX scanning active (manual flow).
 *  - [DispenseStage.PRE_NDC]  : transaction exists, waiting for NDC scan. Reached
 *                               either after RX confirmation (manual flow) or
 *                               directly from a PMS/HL7 notification.
 *  - [DispenseStage.COUNTING] : NDC verified — the rest of the workflow
 *                               (CONTAINER_INITIATE / VIAL / etc.) is driven by the
 *                               existing DispenseCountViewModel.
 *
 * The PMS/HL7 entry path is hydrated by [initializeFromHl7Txn], which reads the
 * existing PMS-created transaction (drugId / targetCount / rxNo / expected NDC)
 * and skips straight to PRE_NDC. The NDC scan then validates against the
 * HL7-expected NDC with the substitute-drug / mismatch dialogs.
 *
 * Stock counts never come here: they run on StockCountScreen and LooseCountScreen.
 * The txn on screen is [DispenseFlowUiState.txnId]; the screen hands it to the count view model.
 */
@HiltViewModel
class DispenseFlowViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val preferenceHelper: PreferenceHelper,
    private val rxLabelResolver: RxLabelResolver,
    private val containerVerifier: ContainerVerifier,
    private val dispenseFlowRepository: DispenseFlowRepository,
    private val auditLogger: AuditLogger,
    private val dispenseStepResolver: DispenseStepResolver,
    private val domainEventBus: DomainEventBus,
) : ViewModel() {

    private val logger = AppLogger("DispenseFlowVM")

    // How the screen was opened: the route's txn and flags.
    private val routeTxnId: Long = savedStateHandle.get<Long>(Screen.DispenseFlow.ARG_TXN_ID) ?: 0L
    private val isFromHl7: Boolean = savedStateHandle.get<Boolean>(Screen.DispenseFlow.ARG_FROM_HL7) ?: false

    private val _uiState = MutableStateFlow(
        DispenseFlowUiState(
            isResolvingEntry = routeTxnId != 0L || isFromHl7,
            isFromQueue = savedStateHandle.get<Boolean>(Screen.DispenseFlow.ARG_FROM_QUEUE) ?: false,
        )
    )
    val uiState: StateFlow<DispenseFlowUiState> = _uiState.asStateFlow()

    private val _effects = Channel<DispenseFlowEffect>(Channel.BUFFERED)
    val effects: Flow<DispenseFlowEffect> = _effects.receiveAsFlow()

    private var queueObserverJob: Job? = null

    // Timestamp of the last VIAL RX-mismatch toast, used to debounce repeated
    // misses on the same wrong vial.
    private var lastVialMismatchToastAt: Long = 0L

    init {
        // Queue mode: initialize the QUEUE stage and start observing dispense transactions.
        // Skip when resuming a specific transaction to avoid the QUEUE stage flash
        // before initializeFromResumedTxn() advances the stage.
        if (_uiState.value.isFromQueue && routeTxnId == 0L) enterQueueMode()
        if (isFromHl7) {
            // PMS/HL7 entry: hydrate from the pre-existing transaction and skip RX.
            // Only runs once — the VM internally early-outs if no txn is found, so we
            // safely fall back to the manual PRE_RX flow.
            initializeFromHl7Txn(routeTxnId)
        } else if (routeTxnId != 0L) {
            // Resume entry: partial transaction already exists, NDC not yet verified.
            // Skip RX scan and go straight to PRE_NDC so the user only scans the container.
            initializeFromResumedTxn(routeTxnId)
        }
    }

    fun onAction(action: DispenseFlowAction) {
        when (action) {
            is DispenseFlowAction.RxLabelScanned -> onRxBarcodeRead(action.rawValue)
            is DispenseFlowAction.ContainerScanned -> onNdcBarcodeRead(action.scannedCode, action.firstBottle)
            DispenseFlowAction.RxLabelScannedOnContainerStep -> onRxScannedInNdcStage()
            is DispenseFlowAction.VialLabelScanned -> onVialBarcodeRead(action.rawValue)
            DispenseFlowAction.RxProceedTapped -> onRxConfirmed()
            DispenseFlowAction.RxCancelled -> onRxCancelled()
            DispenseFlowAction.SubstituteConfirmed -> confirmSubstitute()
            DispenseFlowAction.SubstituteDismissed -> dismissNdcEquivalenceDialog()
            DispenseFlowAction.OnHoldDismissed -> dismissOnHoldDialog()
            is DispenseFlowAction.QueueFilterSelected -> setQueueFilter(action.filter)
            is DispenseFlowAction.QueueItemTapped -> resumeFromQueue(action.txnId)
            DispenseFlowAction.LeavingForQueue -> enterQueueMode()
            DispenseFlowAction.CountFinished -> resetToQueueOrNavigateDashboard()
            is DispenseFlowAction.ResetConfirmed -> returnToContainerScan(action.resetCount)
            is DispenseFlowAction.TourRxNextTapped -> useDummyRxData(action.demoDrugName)
            DispenseFlowAction.TourContainerNextTapped -> useDummyNdcData()
        }
    }

    // ─────────────────────────── Entry ───────────────────────────

    /**
     * Hydrate the screen for an HL7/PMS-initiated dispense. Reads the
     * PMS-created transaction (saved by [com.dispensesure.retail.feature.hl7.data.repository.DispenseHl7Repository])
     * and jumps straight to [DispenseStage.PRE_NDC], with:
     *  - `hl7ExpectedNdc` populated so NDC scan can validate / detect substitutes
     *  - `drugName` / `rxNo` / `targetCount` from the HL7 record
     *
     * If the txn isn't found (e.g. it was cleared between notification post and
     * tap), the screen falls back to the manual PRE_RX flow.
     */
    private fun initializeFromHl7Txn(txnId: Long) {
        viewModelScope.launch {
            try {
                if (txnId == 0L) {
                    logger.w("HL7 init requested but no txnId in the route — falling back to PRE_RX")
                    _uiState.update { it.copy(isResolvingEntry = false) }
                    return@launch
                }
                val txn = dispenseFlowRepository.loadTxn(txnId)
                if (txn == null) {
                    logger.w("HL7 init: txn $txnId not found in DB — falling back to PRE_RX")
                    _uiState.update { it.copy(isResolvingEntry = false) }
                    return@launch
                }
                val drug = dispenseFlowRepository.loadDrug(txn.drugId)
                // This entry always lands on the container scan, whatever the order's progress.
                auditLogger.logOutsideTour(DispenseResumed(txn.txnId, ResumeSource.NOTIFICATION, StepState.SCAN.name))
                _uiState.update {
                    it.withOrder(txn, drug).copy(stage = DispenseStage.PRE_NDC, isResolvingEntry = false)
                }
                logger.i("[HAZARDOUS] HL7 init: txn=$txnId drug=${drug?.drugName} expectedNdc=${drug?.ndc} isHazardous=${drug?.isHazardous ?: false}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("HL7 init failed — falling back to PRE_RX", e, event = LogEvent.DISPENSE_FAILED)
                _uiState.update { it.copy(isResolvingEntry = false) }
            }
        }
    }

    /**
     * Hydrate the screen when resuming an existing partial transaction that has
     * not yet had its NDC verified. Loads [txnId] (from the route) and
     * its drug info, and jumps straight to [DispenseStage.PRE_NDC] so the user
     * only needs to scan the container — the RX stage is skipped entirely.
     */
    private fun initializeFromResumedTxn(txnId: Long) {
        viewModelScope.launch {
            try {
                val txn = dispenseFlowRepository.loadTxn(txnId)
                if (txn == null) {
                    logger.w("Resume init: txn $txnId not found in DB — falling back to PRE_RX")
                    _uiState.update { it.copy(isResolvingEntry = false) }
                    return@launch
                }
                dispenseFlowRepository.clearGlovesPresent(txnId)
                val drug = dispenseFlowRepository.loadDrug(txn.drugId)
                logResumed(txn, drug, ResumeSource.QUEUE)

                if (txn.isNdcVerified == true) {
                    // RX + container both confirmed — skip straight to pill counting.
                    // The order's NDC and bucket too: Reset returns to the container scan.
                    _uiState.update {
                        it.withOrder(txn, drug).copy(stage = DispenseStage.COUNTING, isResolvingEntry = false)
                    }
                    logger.i("[HAZARDOUS] Resume init (NDC verified): txn=$txnId drug=${drug?.drugName} isHazardous=${drug?.isHazardous ?: false} — jumping to COUNTING")
                } else {
                    // Container not yet scanned — land in PRE_NDC so the user only scans the container.
                    _uiState.update {
                        it.withOrder(txn, drug).copy(stage = DispenseStage.PRE_NDC, isResolvingEntry = false)
                    }
                    logger.i("[HAZARDOUS] Resume init (NDC pending): txn=$txnId drug=${drug?.drugName} expectedNdc=${drug?.ndc} isHazardous=${drug?.isHazardous ?: false}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("Resume init failed — falling back to PRE_RX", e, event = LogEvent.DISPENSE_FAILED)
                _uiState.update { it.copy(isResolvingEntry = false) }
            }
        }
    }

    // The order's fields every entry shows; callers set the stage and their own fallbacks.
    private fun DispenseFlowUiState.withOrder(txn: DispenseTxnEntity, drug: DrugMasterEntity?) = copy(
        txnId = txn.txnId,
        drugName = drug?.drugName.orEmpty(),
        ndc = drug?.ndc.orEmpty(),
        hl7ExpectedNdc = drug?.ndc,
        rxNo = txn.rxNo,
        refillNo = txn.refillNo,
        qty = txn.targetCount?.toString(),
        isHazardous = drug?.isHazardous ?: false,
        // Shown on the container-scan step's details bar.
        drugImage = drug?.drugImagePath.orEmpty(),
        // Overwritten with the API value once the NDC is scanned in PRE_NDC.
        ndcStrength = drug?.strength,
        ndcDosageForm = drug?.dosageForm,
        selectedBucketId = txn.bucketId.orEmpty(),
    )

    // ─────────────────────────── Rx label ───────────────────────────

    /**
     * Process a raw barcode read while the user is in PRE_RX. [RxLabelResolver]
     * parses and checks it. On success surfaces the RX bottomsheet.
     */
    private fun onRxBarcodeRead(rawValue: String) {
        if (_uiState.value.stage != DispenseStage.PRE_RX &&
            _uiState.value.stage != DispenseStage.QUEUE) return
        // Advance from QUEUE to PRE_RX so the rest of the RX logic works normally.
        if (_uiState.value.stage == DispenseStage.QUEUE) {
            _uiState.update { it.copy(stage = DispenseStage.PRE_RX) }
        }
        if (_uiState.value.isLoading) return

        _uiState.update { it.copy(isLoading = true) }

        viewModelScope.launch {
            try {
                applyRxScanOutcome(rxLabelResolver.resolve(rawValue))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("RX barcode processing failed", e, event = LogEvent.RX_SCAN_FAILED)
                showGenericError()
            }
        }
    }

    private suspend fun applyRxScanOutcome(outcome: RxScanOutcome) {
        when (outcome) {
            RxScanOutcome.Invalid -> rejectScan(R.string.scan_correct_label, pauseScanning = false)
            RxScanOutcome.AlreadyCompleted -> rejectScan(R.string.rx_already_completed_message, pauseScanning = true)
            RxScanOutcome.NotFound -> rejectScan(R.string.transaction_not_found_toast, pauseScanning = true)
            RxScanOutcome.DrugNotFound -> rejectScan(R.string.rx_label_drug_not_found_toast, pauseScanning = true)
            RxScanOutcome.NdcMismatch -> {
                if (AnalyticsService.isEnabled) {
                    domainEventBus.tryPost(DomainEvent.CountBlocked(DomainEvent.BlockReason.WRONG_DRUG))
                }
                rejectScan(R.string.rescan_ndc_does_not_match_toast, pauseScanning = true)
            }
            RxScanOutcome.OnHold -> _uiState.update { it.copy(isLoading = false, showOnHoldDialog = true) }
            is RxScanOutcome.Resume -> resumeScannedRx(outcome)
            is RxScanOutcome.Standalone -> showStandaloneRxSheet(outcome)
        }
    }

    private suspend fun resumeScannedRx(outcome: RxScanOutcome.Resume) {
        val existingTxn = outcome.txn
        val drug = outcome.drug
        val scannedLabel = outcome.scannedLabel
        // RX found in local DB. The transaction already exists, so
        // we only prep it here (gloves reset, txnId into state).
        //
        // If the container/NDC scan is already done
        // (isNdcVerified), skip the RX verification sheet and land
        // the user directly on the transaction's current step
        // (COUNTING). Otherwise show the sheet and defer the
        // advance to PRE_NDC until the user taps Proceed.
        val txnId = existingTxn.txnId
        dispenseFlowRepository.clearGlovesPresent(txnId)
        val ndcAlreadyVerified = existingTxn.isNdcVerified == true
        val targetStage = if (ndcAlreadyVerified) DispenseStage.COUNTING else DispenseStage.PRE_NDC
        // Container already scanned: the label only reopens the order. Otherwise the verify sheet is shown.
        if (ndcAlreadyVerified) {
            logResumed(existingTxn, drug, ResumeSource.RX_SCAN)
        } else {
            auditLogger.logOutsideTour(RxScanned(txnId, labelNdc = scannedLabel.ndcNo, qty = scannedLabel.qty?.toIntOrNull()))
        }
        _uiState.update {
            it.withOrder(existingTxn, drug).copy(
                isLoading = false,
                // NDC already scanned → go straight to COUNTING.
                // NDC pending → show the sheet, advance on Proceed.
                stage = if (ndcAlreadyVerified) targetStage else it.stage,
                showRxDetails = !ndcAlreadyVerified,
                pendingRxResumeStage = if (ndcAlreadyVerified) null else targetStage,
                rxNo = existingTxn.rxNo ?: scannedLabel.rxNo,
                refillNo = existingTxn.refillNo ?: scannedLabel.refillNo,
                qty = existingTxn.targetCount?.toString() ?: scannedLabel.qty,
                // PMS may send no bucket — fall back to the label's.
                selectedBucketId = existingTxn.bucketId ?: scannedLabel.bucket.orEmpty(),
            )
        }
    }

    /**
     * Shows the verify-Rx sheet for a standalone Rx label.
     * Writes nothing to dispense_txn — [onRxConfirmed] creates the txn on Proceed.
     */
    private fun showStandaloneRxSheet(outcome: RxScanOutcome.Standalone) {
        val draft = outcome.draft
        val drug = outcome.drug
        // No order exists until Proceed, so no txn id; the label's NDC is the matched drug's.
        auditLogger.logOutsideTour(RxScanned(txnId = null, labelNdc = drug.ndc, qty = draft.targetCount))
        // Every field is assigned outright, never falling back to the last scan's value.
        _uiState.update {
            it.copy(
                isLoading = false,
                showRxDetails = true,
                pendingStandaloneRx = draft,
                pendingRxResumeStage = null,
                txnId = 0L,
                drugName = drug.drugName.orEmpty(),
                ndc = drug.ndc,
                hl7ExpectedNdc = drug.ndc,
                rxNo = draft.rxNo,
                refillNo = draft.refillNo,
                qty = draft.targetCount.toString(),
                isHazardous = drug.isHazardous,
                ndcStrength = drug.strength,
                ndcDosageForm = drug.dosageForm,
                drugImage = drug.drugImagePath.orEmpty(),
                selectedBucketId = draft.bucket.orEmpty(),
            )
        }
    }

    /**
     * Tour-only escape hatch for the scan-Rx dummy "Next" button. The RX number is
     * timestamped so repeat tutorial runs never collide with a prior demo dispense.
     */
    private fun useDummyRxData(demoDrugName: String) {
        if (!FtueController.state.isActive) return
        if (_uiState.value.stage != DispenseStage.PRE_RX && _uiState.value.stage != DispenseStage.QUEUE) return
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true) }
        FtueController.track(viewModelScope.launch {
            try {
                if (_uiState.value.stage == DispenseStage.QUEUE) {
                    _uiState.update { it.copy(stage = DispenseStage.PRE_RX) }
                }
                val demoDrug = dispenseFlowRepository.upsertDemoDrug(demoDrugName)
                if (demoDrug == null) {
                    rejectScan(R.string.rx_label_drug_not_found_toast, pauseScanning = true)
                    return@launch
                }
                showStandaloneRxSheet(
                    RxScanOutcome.Standalone(
                        draft = StandaloneRxDraft(
                            drugId = demoDrug.drugId,
                            rxNo = "${TutorialData.RX_PREFIX}${System.currentTimeMillis()}",
                            refillNo = null,
                            bucket = null,
                            targetCount = TUTORIAL_DUMMY_TARGET_COUNT,
                        ),
                        drug = demoDrug,
                    )
                )
            } catch (e: CancellationException) {
                // The tour ended mid-write: drop the loader this call raised.
                _uiState.update { it.copy(isLoading = false) }
                throw e
            }
        })
    }

    /**
     * Called when the user confirms the RX bottomsheet.
     * Resumes the existing txn, or creates the standalone one, then advances to PRE_NDC.
     */
    private fun onRxConfirmed() {
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
            FtueController.track(viewModelScope.launch {
                try {
                    val txnId = dispenseFlowRepository.createStandaloneTxn(draft, preferenceHelper.getLocalId())
                    auditLogger.logOutsideTour(
                        DispenseTxnCreated(
                            txnId = txnId,
                            source = TxnSource.STANDALONE,
                            messageId = null,
                            ndc = state.ndc,
                            targetQty = draft.targetCount,
                            priority = null
                        )
                    )
                    if (AnalyticsService.isEnabled) {
                        domainEventBus.tryPost(DomainEvent.RxLabelProceeded)
                    }
                    _uiState.update { it.copy(stage = DispenseStage.PRE_NDC, txnId = txnId) }
                    logger.i("[HAZARDOUS] Standalone RX confirmed: rx=${draft.rxNo} drugId=${draft.drugId} isHazardous=${state.isHazardous} txn=$txnId → PRE_NDC")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Surface the failure the way the scan-time path used to.
                    logger.e("Standalone dispense txn creation failed", e, event = LogEvent.DISPENSE_FAILED)
                    showGenericError()
                }
            })
            return
        }
    }

    /** Dismiss the RX bottomsheet without creating a transaction. */
    private fun onRxCancelled() {
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

    /** Dismiss the ON_HOLD blocking dialog; user stays on scan screen to try a different RX. */
    private fun dismissOnHoldDialog() {
        _uiState.update { it.copy(showOnHoldDialog = false) }
    }

    // ─────────────────────────── Container ───────────────────────────

    /**
     * Process a raw barcode read while the user is in PRE_NDC.
     * [ContainerVerifier] checks it against the expected NDC.
     */
    private fun onNdcBarcodeRead(scannedCode: String, firstBottle: DispenseBottle?) {
        if (_uiState.value.stage != DispenseStage.PRE_NDC) return
        if (_uiState.value.isLoading) return
        if (scannedCode.isBlank()) {
            rejectScan(R.string.scan_correct_label, pauseScanning = false)
            return
        }

        _uiState.update { it.copy(isLoading = true, pendingFirstBottle = firstBottle) }

        FtueController.track(viewModelScope.launch {
            try {
                // Expected NDC comes from HL7 in the PMS flow, and from the RX
                // label the user just scanned in the manual flow. Either way,
                // the container scan has to match it (or be a server-flagged
                // substitute) before counting starts.
                val expectedNdc = _uiState.value.hl7ExpectedNdc
                    ?.takeIf { it.isNotBlank() }
                    ?: _uiState.value.ndc.takeIf { it.isNotBlank() }

                applyContainerOutcome(containerVerifier.verify(scannedCode, expectedNdc), expectedNdc)
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isLoading = false, pendingFirstBottle = null) }
                throw e
            } catch (e: Exception) {
                logger.e("NDC barcode processing failed", e, event = LogEvent.NDC_SCAN_FAILED)
                showGenericError()
            }
        })
    }

    /**
     * Tour-only escape hatch for the scan-container dummy "Next" button: feeds the
     * demo NDC through the real [onNdcBarcodeRead], which resolves locally with no
     * server round-trip. No first bottle: there is no real label to record.
     */
    private fun useDummyNdcData() {
        if (!FtueController.state.isActive) return
        onNdcBarcodeRead(TutorialData.DEMO_NDC, firstBottle = null)
    }

    /** Applies a container check: saved match, server match, substitute, mismatch or not found. */
    private suspend fun applyContainerOutcome(outcome: ContainerOutcome, expectedNdc: String?) {
        when (outcome) {
            is ContainerOutcome.SavedMatch -> {
                val drug = outcome.drug
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        ndcScannedValue = drug.ndc,
                        ndcDrugName = drug.drugName.orEmpty(),
                        ndcPackageQty = drug.packageQty,
                        isHazardous = drug.isHazardous,
                    )
                }
                logger.i("[HAZARDOUS] NDC scan → local DB: ndc=${drug.ndc} drug=${drug.drugName} isHazardous=${drug.isHazardous}")
                advanceToCountingStage()
            }
            is ContainerOutcome.Substitute -> {
                val drug = outcome.drug
                val displayName = drug.drugName.orEmpty()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        ndcScannedValue = drug.ndc,
                        ndcDrugName = displayName,
                        ndcPackageQty = drug.packageQty,
                        ndcDrugType = drug.drugType,
                        ndcStrength = drug.strength,
                        drugImage = outcome.imageUrl ?: it.drugImage,
                        ndcDosageForm = drug.dosageForm,
                        showNdcEquivalenceDialog = true,
                        isHazardous = drug.isHazardous,
                    )
                }
                logger.i("[HAZARDOUS] NDC scan → substitute: serverNdc=${drug.ndc} drug=$displayName isHazardous=${drug.isHazardous}")
            }
            is ContainerOutcome.Mismatch -> {
                // Surface a non-blocking toast and let the user
                // rescan — the popup-style dialog was too heavy for this case.
                val drug = outcome.drug
                logger.w("[HAZARDOUS] NDC scan → mismatch: serverNdc=${drug.ndc} expectedNdc=$expectedNdc isHazardous=${drug.isHazardous}")
                if (AnalyticsService.isEnabled) {
                    domainEventBus.tryPost(DomainEvent.CountBlocked(DomainEvent.BlockReason.WRONG_DRUG))
                }
                rejectScan(R.string.rescan_ndc_does_not_match_toast, pauseScanning = true)
            }
            is ContainerOutcome.ServerMatch -> {
                val drug = outcome.drug
                val displayName = drug.drugName.orEmpty()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        ndcScannedValue = drug.ndc,
                        ndcDrugName = displayName,
                        ndcPackageQty = drug.packageQty,
                        ndcStrength = drug.strength,
                        drugImage = outcome.imageUrl ?: it.drugImage,
                        ndcDosageForm = drug.dosageForm,
                        isHazardous = drug.isHazardous,
                    )
                }
                logger.i("[HAZARDOUS] NDC scan → server match: ndc=${drug.ndc} drug=$displayName isHazardous=${drug.isHazardous}")
                advanceToCountingStage()
            }
            ContainerOutcome.NotFound -> rejectScan(R.string.the_scanned_ndc_does_not_match, pauseScanning = false)
            ContainerOutcome.LookupFailed -> showGenericError()
        }
    }

    /**
     * Confirm a substitute (generic-equivalent) drug. Closes the equivalence
     * dialog, marks the substitute flag, and moves on to counting the way a
     * straight match does.
     */
    private fun confirmSubstitute() {
        _uiState.update {
            it.copy(
                showNdcEquivalenceDialog = false,
                isSubstituteConfirmed = true,
            )
        }
        viewModelScope.launch {
            try {
                advanceToCountingStage()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("Advance to counting stage failed after substitute confirm", e, event = LogEvent.NDC_SCAN_FAILED)
                _effects.trySend(DispenseFlowEffect.GenericError)
            }
        }
    }

    private fun dismissNdcEquivalenceDialog() {
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

    private suspend fun advanceToCountingStage() {
        val state = _uiState.value
        val txnId = state.txnId

        // No dispense txn to mark as verified.
        if (txnId == 0L) return

        val isSubstitute = state.isSubstituteConfirmed
        val substituteDrug = if (isSubstitute && state.ndcScannedValue.isNotBlank()) {
            DrugMasterEntity(
                ndc = state.ndcScannedValue,
                drugName = state.ndcDrugName.ifBlank { null },
                drugType = state.ndcDrugType,
                packageQty = state.ndcPackageQty,
                isHazardous = state.isHazardous,
                strength = state.ndcStrength,
                dosageForm = state.ndcDosageForm,
            )
        } else null
        val isVerified = dispenseFlowRepository.markContainerVerified(
            txnId = txnId,
            isSubstitute = isSubstitute,
            substituteDrug = substituteDrug,
            firstBottle = state.pendingFirstBottle,
        )
        if (!isVerified) return
        if (isSubstitute) {
            auditLogger.logOutsideTour(SubstituteAccepted(txnId, expectedNdc = state.hl7ExpectedNdc ?: state.ndc, scannedNdc = state.ndcScannedValue))
        }
        _uiState.update { it.copy(stage = DispenseStage.COUNTING, pendingFirstBottle = null) }
        logger.i("[HAZARDOUS] NDC auto-confirmed: txn=$txnId substitute=$isSubstitute isHazardous=${state.isHazardous} → COUNTING")
    }

    /**
     * Called from the screen when, during PRE_NDC, the user scans something
     * that looks like an RX label (pipe-delimited template, not a GTIN-14).
     * The legacy single-screen flow had no way to surface this; the merged
     * dispense flow now nudges the user with a toast instead of silently
     * dropping the read.
     */
    private fun onRxScannedInNdcStage() {
        if (_uiState.value.stage != DispenseStage.PRE_NDC) return
        rejectScan(R.string.scan_ndc_toast, pauseScanning = true)
    }

    // ─────────────────────────── Reset ───────────────────────────

    /**
     * Sends the flow back to the container scan after a reset. The Rx details stay —
     * it is the same order — only the container scan's own state is cleared.
     *
     * [resetCount] runs first and on this scope, so leaving the screen mid-reset cannot
     * strand a wiped transaction with the stage still on COUNTING. If it returns false the
     * reset failed, so the stage is left where it is.
     */
    private fun returnToContainerScan(resetCount: suspend () -> Boolean) {
        viewModelScope.launch {
            try {
                if (!resetCount()) return@launch
                // The container scan replaced drugImage with the server's image URL, but
                // the details bar loads a local file. Take the drug row's own path back,
                // the same value the counting header reads.
                // Same txn id resetForRecount works from, so both halves of a reset
                // agree on which transaction they are restarting.
                val txnId = _uiState.value.txnId
                // The count is already wiped: a failed lookup only loses the display fallbacks, so still move the stage.
                val drug = try {
                    txnId.takeIf { it != 0L }?.let { dispenseFlowRepository.loadOrderedDrug(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.e("Drug lookup for container scan failed", e, event = LogEvent.NDC_SCAN_FAILED)
                    null
                }

                _uiState.update {
                    it.copy(
                        stage = DispenseStage.PRE_NDC,
                        ndcScannedValue = "",
                        ndcDrugName = "",
                        ndcPackageQty = null,
                        ndcDrugType = null,
                        pendingFirstBottle = null,
                        isSubstituteConfirmed = false,
                        showNdcEquivalenceDialog = false,
                        drugImage = drug?.drugImagePath ?: it.drugImage,
                        ndcStrength = drug?.strength ?: it.ndcStrength,
                        ndcDosageForm = drug?.dosageForm ?: it.ndcDosageForm,
                    )
                }
                logger.i("Reset: returning to container scan for txn=${_uiState.value.txnId}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("Return to container scan failed", e, event = LogEvent.NDC_SCAN_FAILED)
                _effects.trySend(DispenseFlowEffect.GenericError)
            }
        }
    }

    // ─────────────────────────── Vial ───────────────────────────

    /**
     * Process a vial barcode read during the VIAL step. The vial label carries
     * the same RX-label barcode the user scanned at PRE_RX, so we extract the RX
     * number and compare it to the active transaction's [DispenseFlowUiState.rxNo].
     *
     * Sends [DispenseFlowEffect.VialLabelMatched] when the RX matches — the screen
     * then triggers the automatic photo capture (mirroring a tap on the camera button)
     * and does NOT resume the analyzer. Sends [DispenseFlowEffect.ScanRejected] when
     * the barcode can't be parsed or the RX doesn't match; on a mismatch a debounced
     * toast is surfaced and the screen resumes the analyzer to keep scanning. Manual
     * capture stays available regardless.
     */
    private fun onVialBarcodeRead(rawValue: String) {
        if (rawValue.isBlank()) {
            readVialLabelAgain()
            return
        }
        val expectedRx = _uiState.value.rxNo?.trim().orEmpty()
        if (expectedRx.isEmpty()) {
            readVialLabelAgain()
            return
        }

        // Pipe-delimited payloads are the RX-label template; otherwise the raw
        // value may already be the bare RX number.
        val scannedRx = if (rawValue.contains('|')) {
            rxLabelResolver.rxNoOf(rawValue).orEmpty()
        } else {
            rawValue.trim()
        }
        if (scannedRx.isEmpty()) {
            readVialLabelAgain()
            return
        }

        if (scannedRx.equals(expectedRx, ignoreCase = true)) {
            _effects.trySend(DispenseFlowEffect.VialLabelMatched)
            return
        }
        // The analyzer self-pauses then resumes on every miss, so the same
        // wrong vial would otherwise spam toasts — debounce them.
        val now = System.currentTimeMillis()
        if (now - lastVialMismatchToastAt > VIAL_MISMATCH_TOAST_COOLDOWN_MS) {
            lastVialMismatchToastAt = now
            rejectScan(R.string.vial_rx_mismatch_toast, pauseScanning = true)
        } else {
            readVialLabelAgain()
        }
        logger.w("VIAL barcode RX mismatch: scanned=$scannedRx expected=$expectedRx")
    }

    // Not a match and no toast: the screen reads the next frame at once.
    private fun readVialLabelAgain() {
        rejectScan(toastText = null, pauseScanning = false)
    }

    // ─────────────────────────── Queue mode ───────────────────────────

    private fun enterQueueMode() {
        observeDispenseQueue()
        _uiState.update {
            DispenseFlowUiState(
                stage = DispenseStage.QUEUE,
                isFromQueue = it.isFromQueue,
                queueItems = it.queueItems,
                selectedQueueFilter = it.selectedQueueFilter ?: KpiFilter.DISP_PENDING,
            )
        }
    }

    private suspend fun hasPendingDispenseItems(): Boolean =
        dispenseFlowRepository.hasPendingDispenses(preferenceHelper.getLocalId())

    private fun resetToQueueOrNavigateDashboard() {
        viewModelScope.launch {
            // Tutorial: the tour's next step lives on the Dashboard (tap Stock
            // Count), never the Queue — a pending item (real, or a leftover from
            // earlier testing) routing here to the Queue instead would silently
            // strand the tour with nothing on screen to spotlight and no way
            // forward. Always go to the Dashboard while the tour is active.
            try {
                if (!FtueController.state.isActive && hasPendingDispenseItems()) {
                    enterQueueMode()
                } else {
                    _effects.trySend(DispenseFlowEffect.LeaveToDashboard)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("Queue/dashboard routing check failed — defaulting to Dashboard", e, event = LogEvent.DISPENSE_FAILED)
                _effects.trySend(DispenseFlowEffect.LeaveToDashboard)
            }
        }
    }

    private fun setQueueFilter(filter: KpiFilter?) {
        // Tabs always have one selection — clicking the active tab keeps it selected.
        if (filter != null) {
            _uiState.update { it.copy(selectedQueueFilter = filter) }
        }
    }

    private fun resumeFromQueue(txnId: Long) {
        viewModelScope.launch {
            try {
                val txn = dispenseFlowRepository.loadTxn(txnId) ?: return@launch
                dispenseFlowRepository.clearGlovesPresent(txnId)
                val drug = dispenseFlowRepository.loadDrug(txn.drugId)
                logResumed(txn, drug, ResumeSource.QUEUE)
                val targetStage = if (txn.isNdcVerified == true) DispenseStage.COUNTING else DispenseStage.PRE_NDC
                _uiState.update { it.withOrder(txn, drug).copy(stage = targetStage) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("Resume from queue failed for txn=$txnId", e, event = LogEvent.DISPENSE_RESUME_FAILED)
                _effects.trySend(DispenseFlowEffect.GenericError)
            }
        }
    }

    // Logs the reopened order with the step it lands on, the same one the count screen picks.
    private suspend fun logResumed(txn: DispenseTxnEntity, drug: DrugMasterEntity?, source: ResumeSource) {
        val step = dispenseStepResolver.resumeStep(txn.txnId, txn.isNdcVerified == true, txn.workflowStep, drug?.drugType)
        auditLogger.logOutsideTour(DispenseResumed(txn.txnId, source, step.name))
    }

    private fun observeDispenseQueue() {
        queueObserverJob?.cancel()
        queueObserverJob = viewModelScope.launch {
            dispenseFlowRepository.observeQueue(preferenceHelper.getLocalId())
                .catch { e -> logger.e("Dispense queue observation failed", e, event = LogEvent.DISPENSE_FAILED) }
                .collect { queueItems ->
                    _uiState.update { it.copy(queueItems = queueItems) }
                }
        }
    }

    // ─────────────────────────── Effects ───────────────────────────

    // Turns a scan away: loading ends, and the screen toasts (if any) and reads again.
    private fun rejectScan(@StringRes toastText: Int?, pauseScanning: Boolean) {
        _uiState.update { it.copy(isLoading = false) }
        _effects.trySend(DispenseFlowEffect.ScanRejected(toastText, pauseScanning))
    }

    // A failed scan or save: loading ends and the generic error shows. The cause is logged, not shown.
    private fun showGenericError() {
        _uiState.update { it.copy(isLoading = false) }
        _effects.trySend(DispenseFlowEffect.GenericError)
    }
}

private const val VIAL_MISMATCH_TOAST_COOLDOWN_MS = 2000L

private const val TUTORIAL_DUMMY_TARGET_COUNT = 5
