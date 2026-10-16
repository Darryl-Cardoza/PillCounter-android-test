package com.dispensesure.retail.feature.dispense.presentation.viewmodel

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dispensesure.retail.R
import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.room.models.DrugMasterEntity
import com.dispensesure.retail.core.room.models.dtos.DispenseTxnDetail
import com.dispensesure.retail.core.scanning.domain.model.DispenseBottle
import com.dispensesure.retail.core.scanning.domain.model.TxnDetail
import com.dispensesure.retail.core.scanning.engine.CaptureResult
import com.dispensesure.retail.core.scanning.engine.EngineEvent
import com.dispensesure.retail.core.scanning.engine.EngineState
import com.dispensesure.retail.core.scanning.engine.PillCountingEngine
import com.dispensesure.retail.core.scanning.engine.toastText
import com.dispensesure.retail.core.scanning.logic.ScanDecoder
import com.dispensesure.retail.core.tourguide.FtueController
import com.dispensesure.retail.core.tourguide.TourActionTimeoutMs
import com.dispensesure.retail.core.utils.common.SoundUtils
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.logger.LogEvent
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import com.dispensesure.retail.feature.dispense.data.DispenseCountRepository
import com.dispensesure.retail.feature.dispense.domain.BottleRescanDecider
import com.dispensesure.retail.feature.dispense.domain.DispenseWorkflow
import com.dispensesure.retail.feature.dispense.domain.DoneOutcome
import com.dispensesure.retail.feature.dispense.domain.FinishOutcome
import com.dispensesure.retail.feature.dispense.domain.RescanOutcome
import com.dispensesure.retail.feature.dispense.domain.model.DispenseCountAction
import com.dispensesure.retail.feature.dispense.domain.model.DispenseCountEffect
import com.dispensesure.retail.feature.dispense.domain.model.DispenseCountUiState
import com.dispensesure.retail.feature.dispense.domain.model.DispenseStage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.ArrayDeque
import javax.inject.Inject

/**
 * ViewModel responsible for:
 * - Managing camera frame analysis (via [PillCountingEngine]).
 * - Updating UI state for pill counting workflow.
 * - Running the step rules ([DispenseWorkflow], [BottleRescanDecider]) and the data work ([DispenseCountRepository]).
 * - Preventing duplicate "Add" operations without a new scan.
 */
@HiltViewModel
class DispenseCountViewModel @Inject constructor(
    private val preferenceHelper: PreferenceHelper,
    private val scanDecoder: ScanDecoder,
    private val dispenseCountRepository: DispenseCountRepository,
    engineFactory: PillCountingEngine.Factory,
) : ViewModel() {

    private val logger = AppLogger("DispenseCountVM")
    // Camera, ML, idle, gloves, tray and the Add photo. Declared before anything that reads it.
    private val engine = engineFactory.create(viewModelScope)

    private val _uiState = MutableStateFlow(
        DispenseCountUiState(isVoiceOverEnabled = preferenceHelper.isSoundOverride())
    )
    val uiState: StateFlow<DispenseCountUiState> = _uiState.asStateFlow()

    private val _effects = Channel<DispenseCountEffect>(Channel.BUFFERED)
    val effects: Flow<DispenseCountEffect> = _effects.receiveAsFlow()

    /** The engine's live camera state, for the overlay, the circle, the idle scrim and the tray popup. */
    val engineState: StateFlow<EngineState> = engine.state

    /** Public read-only flow for observing recent detection counts. */
    val recentDetectionCounts: StateFlow<ArrayDeque<Int>> = engine.lastTenDetections

    val addPops: Flow<Int> = engine.addPops

    // The txn being counted, as last loaded. Null before counting starts and after a reset.
    private var loadedTxn: DispenseTxnDetail? = null

    // --- Dispense-flow bottle tracking (rescan-same-NDC during counting) ---
    // No in-memory cache of the bottle list: every scan re-reads
    // DispenseTxnEntity.bottlesJson fresh from Room, so a resumed/killed-and-restarted
    // process always compares against the real persisted state. isProcessingBottleScan only
    // guards against the same physical scan firing this handler multiple times in a row
    // (camera frame analyzer re-detecting the same barcode across consecutive frames) while a
    // scan is already being resolved or its confirm dialog is awaiting a user tap.
    @Volatile private var isProcessingBottleScan = false
    private var pendingBottleScan: DispenseBottle? = null

    // True once the still on screen has been written, so a Done re-tap cannot write it twice.
    private var captureCommitted = false

    /** Tour: set once Done goes straight to confirm (notes setting off), so the VIAL wait ends. */
    private var confirmDoneStarted = false

    private var stepCountsJob: Job? = null
    private var resetAvailabilityJob: Job? = null

    init {
        // A new count starts with gloves unconfirmed.
        engine.resetGloves()
        viewModelScope.launch { engine.events.collect { engineEvent -> onEngineEvent(engineEvent) } }
    }

    /** True when the backend's colour-image setting is off, so saved photos go grayscale. */
    fun isGrayscaleImage(): Boolean = engine.isGrayscale()

    fun onAction(action: DispenseCountAction) {
        when (action) {
            is DispenseCountAction.StageChanged -> onStageChanged(action.stage, action.isHazardousDrug)
            is DispenseCountAction.FlowOverlayChanged ->
                if (action.isShown) engine.pauseDetection() else engine.resumeDetection()
            is DispenseCountAction.HistoryVisibilityChanged -> onHistoryVisibilityChanged(action.isShown)
            is DispenseCountAction.LeavingForQueue -> {
                if (action.resetGloves) engine.resetGloves()
                resetWorkflowSteps()
            }
            DispenseCountAction.ScreenTouched -> engine.noteActivity()
            DispenseCountAction.CountSteadied -> engine.playCountSoundIfEnabled()
            is DispenseCountAction.CameraHelperReady -> {
                engine.attachCamera(action.cameraHelper)
                // A capture lost to an unbound camera never calls back, so clear the stuck flag here.
                _uiState.update { it.copy(isCapturing = false) }
            }
            DispenseCountAction.PreviewSizeKnown -> engine.start()
            is DispenseCountAction.FrameArrived -> engine.onFrame(action.image)
            is DispenseCountAction.FilteredPillsDrawn -> engine.onFilteredPills(action.filteredPills)
            DispenseCountAction.ResumeFromIdleTapped -> engine.resumeFromIdle()
            is DispenseCountAction.TrayClassified -> engine.classifyTray(action.color, action.isHazardous)
            is DispenseCountAction.AddTapped -> onAddTapped(action.detectedCount)
            DispenseCountAction.DoneTapped -> onDoneTapped()
            is DispenseCountAction.CountDeleted -> deleteCount(action.countId)
            is DispenseCountAction.BottleScanned -> onBottleScanned(action.rawValue, action.imagePath)
            DispenseCountAction.VialCaptureTapped -> captureVialStill(autoConfirm = false)
            // Only auto-commit when VIAL is the last step; otherwise wait for Done.
            DispenseCountAction.VialLabelMatched -> captureVialStill(autoConfirm = isVialLastStep())
            DispenseCountAction.VialRedoTapped -> redoVialStill()
            DispenseCountAction.VialSaveTapped -> _uiState.value.capturedStill?.let { vialStill -> commitVialStill(vialStill) }
            is DispenseCountAction.TargetCountEntered -> onTargetCountEntered(action.targetCount)
            DispenseCountAction.TargetCountDismissed -> _uiState.update { it.copy(showTargetCountDialog = false) }
            DispenseCountAction.NoteDismissed -> _uiState.update { it.copy(showNotesDialog = false) }
            DispenseCountAction.NoteSkipped -> {
                _uiState.update { it.copy(showNotesDialog = false) }
                completeDispense()
            }
            is DispenseCountAction.NoteSaved -> onNoteSaved(action.note)
            DispenseCountAction.CountMismatchConfirmed -> moveNextStep()
            DispenseCountAction.CountMismatchDismissed -> {
                engine.resumeFromIdle()
                _uiState.update { it.copy(showCountMismatchDialog = false) }
            }
            DispenseCountAction.AddBottleConfirmed -> onAddBottleConfirmed()
            DispenseCountAction.AddBottleDismissed -> dismissBottleDialog(isAddDialog = true)
            DispenseCountAction.ReplaceBottleConfirmed -> onReplaceBottleConfirmed()
            DispenseCountAction.ReplaceBottleDismissed -> dismissBottleDialog(isAddDialog = false)
            DispenseCountAction.SkipBackCountTapped -> {
                // Single-button "Skip": finish the txn without entering CONTAINER_PENDING.
                _uiState.update { it.copy(showSkipStepDialog = false) }
                finishDispense()
            }
        }
    }

    // ─────────────────────────── Stage and camera ───────────────────────────

    // Pill detection runs on every stage; only the counting stage loads the workflow.
    private fun onStageChanged(stage: DispenseStage, isHazardousDrug: Boolean) {
        engine.resumeDetection()
        when (stage) {
            DispenseStage.COUNTING -> startCounting(isHazardousDrug)
            // The workflow is only built when counting starts, so the SCAN step has no
            // steps to show. Land on SCAN as the user gets here.
            DispenseStage.PRE_NDC -> enterScanStep()
            // Clear workflow step icons when returning to queue so COUNTING steps
            // don't linger.
            DispenseStage.QUEUE -> resetWorkflowSteps()
            DispenseStage.PRE_RX -> Unit
        }
    }

    private fun startCounting(isHazardousDrug: Boolean) {
        loadWorkflow()
        loadTxnDetails()
        observeResetAvailability()
        // Enable tray color detection for all transactions (hazardous and non-hazardous).
        logger.i("Calling setHazardousTransaction(isHazardous=$isHazardousDrug)")
        engine.configureHazardous(isHazardousDrug)
        // Load glove model only now that RX + NDC are confirmed and drug is hazardous.
        if (isHazardousDrug) {
            logger.i("Hazardous drug — loading glove model")
            engine.loadGloveModel()
        }
    }

    // View All hides the camera: no idle pause and no detection behind it.
    private fun onHistoryVisibilityChanged(isShown: Boolean) {
        if (isShown) engine.stopIdleTimer() else engine.armIdleTimer()
        engine.setCameraPaused(isShown)
    }

    /** Saves the engine's gloves / tray results on the dispense txn and shows its tray warnings; the +N pop goes straight to the screen. */
    private fun onEngineEvent(event: EngineEvent) {
        when (event) {
            EngineEvent.GlovesConfirmed -> saveGlovesPresent()
            is EngineEvent.HazardousTrayResult -> saveHazardousTrayDetected(event.detected)
            is EngineEvent.Warning -> showToast(event.messageRes)
        }
    }

    private fun saveHazardousTrayDetected(detected: Boolean) {
        viewModelScope.launch {
            val txnId = preferenceHelper.getTxnId()
            if (txnId != 0L) {
                dispenseCountRepository.saveHazardousTrayDetected(txnId, detected)
                logger.i("hazardousTrayDetected=$detected saved for txnId=$txnId")
                logger.d("DB updated: hazardousTrayDetected=$detected txnId=$txnId")
            }
        }
    }

    /** Saves the engine's glove lock-in on the current dispense txn. */
    private fun saveGlovesPresent() {
        viewModelScope.launch {
            try {
                val txnId = preferenceHelper.getTxnId()
                if (txnId != 0L) {
                    dispenseCountRepository.saveGlovesPresent(txnId)
                    logger.i("isGlovesPresent saved for txn=$txnId")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("Failed to save glovesPresent flag", e, event = LogEvent.GLOVE_DETECT_FAILED)
            }
        }
    }

    private fun showToast(@StringRes text: Int) {
        _effects.trySend(DispenseCountEffect.ShowToast(text))
    }

    private fun showGenericError() = showToast(R.string.error_generic)

    // ─────────────────────────── Workflow ───────────────────────────

    /** Builds the steps and picks the one counting starts on. */
    private fun loadWorkflow() {
        viewModelScope.launch {
            try {
                val txnInfo = dispenseCountRepository.loadTxn(preferenceHelper.getTxnId())
                keepLoadedTxn(txnInfo)
                val drugInfo = dispenseCountRepository.loadDrug(txnInfo?.drugId)
                val steps = workflowSteps(txnInfo, drugInfo)
                val savedWorkflowStep = txnInfo?.workflowStep
                    ?.let { runCatching { StepState.valueOf(it) }.getOrNull() }
                val resolvedStep = DispenseWorkflow.startStep(
                    steps = steps,
                    savedStep = savedWorkflowStep,
                    latestCountStep = dispenseCountRepository.latestCountStep(preferenceHelper.getTxnId()),
                    drugType = drugInfo?.drugType,
                )

                _uiState.update { it.copy(steps = steps, currentStep = resolvedStep) }
                if (resolvedStep == StepState.VIAL) {
                    engine.pauseDetection()
                    loadExistingVialPhoto(preferenceHelper.getTxnId())
                }
                observeStepCounts(resolvedStep)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("Failed to load drug info / resolve workflow step", e, event = LogEvent.PILL_COUNT_FAILED)
            }
        }
    }

    /** The dispense's steps, from its drug and the double-count / back-count settings. */
    private fun workflowSteps(txnInfo: DispenseTxnDetail?, drugInfo: DrugMasterEntity?): List<StepState> =
        DispenseWorkflow.steps(
            isTxnLoaded = txnInfo != null,
            drugType = drugInfo?.drugType,
            doubleCountOn = preferenceHelper.isRequireDoubleCountEnabled(),
            controlDrugTypes = preferenceHelper.getControlDrugTypes(),
            backCountOn = preferenceHelper.isRequireBackCountEnabled(),
        )

    /** Loads the drug details and the target; asks for a target when the order has none. */
    private fun loadTxnDetails() {
        viewModelScope.launch {
            try {
                val txnInfo = dispenseCountRepository.loadTxn(preferenceHelper.getTxnId())
                keepLoadedTxn(txnInfo)

                val shouldShowDialog =
                    (txnInfo?.targetCount == null || txnInfo.targetCount == 0) && !_uiState.value.showTargetCountDialog

                _uiState.update {
                    it.copy(
                        drugName = txnInfo?.drugName.orEmpty(),
                        ndc = txnInfo?.ndc.orEmpty(),
                        strength = txnInfo?.strength.orEmpty(),
                        dosageForm = txnInfo?.dosageForm.orEmpty(),
                        bucketId = txnInfo?.bucketId?.takeIf { b -> b.isNotBlank() },
                        targetCount = txnInfo?.targetCount ?: 0,
                        showTargetCountDialog = shouldShowDialog,
                        drugImage = txnInfo?.drugImage.orEmpty()
                    )
                }
                logger.d("Txn info loaded. Drug=${txnInfo?.drugName}, Target=${txnInfo?.targetCount}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("Failed to load txn info", e, event = LogEvent.PILL_COUNT_FAILED)
            }
        }
    }

    // Both loads keep the txn they read, as both set the old txn flow.
    private fun keepLoadedTxn(txnInfo: DispenseTxnDetail?) {
        loadedTxn = txnInfo
        _uiState.update { it.copy(isTxnLoaded = txnInfo != null) }
    }

    /** Observe the current step's counts; on the back count, also work out what should be left. */
    private fun observeStepCounts(step: StepState) {
        stepCountsJob?.cancel()

        stepCountsJob = viewModelScope.launch {
            dispenseCountRepository.observeCounts(preferenceHelper.getTxnId(), step)
                .catch { e -> logger.e("Failed observing txn details for step=$step", e, event = LogEvent.PILL_COUNT_FAILED) }
                .collectLatest { stepCounts ->
                    _uiState.update { state ->
                        state.copy(stepCounts = stepCounts)
                    }

                    if (step == StepState.CONTAINER_PENDING) {
                        val countedPills = dispenseCountRepository.stepPillTotal(
                            preferenceHelper.getTxnId(), StepState.CONTAINER_INITIATE
                        )

                        val remainingPills = countedPills - (loadedTxn?.targetCount ?: 0)

                        _uiState.update {
                            it.copy(targetCount = remainingPills.coerceAtLeast(0))
                        }
                    }
                }
        }
    }

    private fun observeResetAvailability() {
        resetAvailabilityJob?.cancel()
        resetAvailabilityJob = viewModelScope.launch {
            val txnId = preferenceHelper.getTxnId()
            dispenseCountRepository.observeCanReset(txnId)
                .catch { e -> logger.e("Failed observing txn reset availability. txnId=$txnId", e, event = LogEvent.PILL_COUNT_RESET_FAILED) }
                .collect { canReset ->
                    _uiState.update { it.copy(canReset = canReset) }
                }
        }
    }

    /**
     * Lands the workflow on the SCAN step: publishes the steps and marks SCAN as the
     * current one, so the stepper can be shown there like any other step.
     * [loadWorkflow] reassigns both when counting starts. The saved workflowStep column
     * is deliberately not touched — a resume must not come back to SCAN.
     */
    private fun enterScanStep() {
        // Set first, so the stepper never renders a stale step while the steps load.
        _uiState.update { it.copy(currentStep = StepState.SCAN) }
        viewModelScope.launch {
            try {
                val txnInfo = dispenseCountRepository.loadTxn(preferenceHelper.getTxnId())
                val steps = workflowSteps(txnInfo, dispenseCountRepository.loadDrug(txnInfo?.drugId))
                _uiState.update { it.copy(steps = steps) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("Failed to resolve workflow steps entering SCAN step", e, event = LogEvent.PILL_COUNT_FAILED)
            }
        }
    }

    /** Clear the workflow step list so the WorkflowStepper hides when returning to QUEUE/PRE_RX. */
    private fun resetWorkflowSteps() {
        _uiState.update { it.copy(steps = emptyList(), currentStep = StepState.TARGET_VERIFICATION) }
    }

    private fun moveNextStep() {
        val state = _uiState.value
        val next = DispenseWorkflow.next(state.steps, state.currentStep)
        if (next == null) {
            finishDispense(); return
        }

        if (next == StepState.VIAL) {
            engine.pauseDetection()
            // The VIAL step is a still-photo capture: the live counting camera is
            // intentionally off, so no frames arrive to keep the idle watchdog
            // alive. Cancel it here, otherwise it fires after ~60s on the photo
            // screen and leaves the "Counting paused / RESUME" overlay up once the
            // user finishes the vial photo (the overlay is cleared again in
            // commitVialStill when leaving VIAL).
            engine.stopIdleTimer()
        }
        if (next == StepState.CONTAINER_PENDING) {
            redoVialStill()
            engine.resumeFromIdle()
            _uiState.update { it.copy(targetCount = loadedTxn?.targetCount ?: 0) }
        }

        // Clear the previous step's history/running total BEFORE wiring up the
        // new step's observer. Each step counts into its own StepState rows, so
        // on entry the new step starts at 0. observeStepCounts() only
        // overwrites uiState once its first DB emission lands; without this reset
        // the panel briefly shows the prior step's count (e.g. "30/30" on the
        // first TARGET_VERIFICATION entry instead of "0/30").
        _uiState.update {
            it.copy(
                currentStep = next,
                stepCounts = emptyList(),
            )
        }
        observeStepCounts(next)

        viewModelScope.launch {
            try {
                dispenseCountRepository.saveStep(preferenceHelper.getTxnId(), next)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("Failed to persist workflow step=$next", e, event = LogEvent.PILL_COUNT_FAILED)
            }
        }
    }

    // ─────────────────────────── ADD, Done, View All ───────────────────────────

    private fun onAddTapped(detectedCount: Int) {
        val state = _uiState.value
        val step = state.currentStep
        val predictedTotal = state.totalCount + detectedCount
        val skipRestriction = step in listOf(StepState.CONTAINER_INITIATE)

        if (!skipRestriction) {
            if (predictedTotal > state.targetCount) {
                showToast(R.string.max_count_reached)
                logger.w("Add blocked: predicted total exceeds target count.")
                return
            }
        }
        val capturedAdd = when (val captureResult = engine.captureCount(detectedCount)) {
            is CaptureResult.Rejected -> {
                showToast(captureResult.reason.toastText())
                return
            }
            is CaptureResult.Captured -> captureResult
        }
        logger.i("Adding transaction detail. Count=$detectedCount")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val txnId = preferenceHelper.getTxnId()
                val photoLabel = dispenseCountRepository.countPhotoLabel(txnId, step)
                // TARGET_VERIFICATION also keeps the crop with nothing drawn on it, for PMS.
                val savedPhoto = engine.saveCountPhoto(
                    capturedAdd,
                    photoLabel,
                    keepRawCrop = step == StepState.TARGET_VERIFICATION,
                )

                // Persist each ADD immediately so a session backed out before Done is
                // still saved (shows under Pending). The DB observer
                // (observeStepCounts) drives uiState.
                dispenseCountRepository.addCount(txnId, step, detectedCount, savedPhoto)

                logger.i("Transaction detail INSERTED (immediate). Count=$detectedCount, File=${savedPhoto.imagePath}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("Failed to add transaction detail", e, event = LogEvent.PILL_COUNT_FAILED)
                // Let the same pills be added again.
                engine.forgetLastAdd()
                showGenericError()
            } finally {
                if (!capturedAdd.frame.isRecycled) capturedAdd.frame.recycle()
            }
        }
    }

    private fun onDoneTapped() {
        val state = _uiState.value
        val doneOutcome = DispenseWorkflow.onDone(
            step = state.currentStep,
            countedTotal = state.totalCount,
            stepTarget = state.targetCount,
            orderTarget = loadedTxn?.targetCount,
            isTxnLoaded = loadedTxn != null,
        )
        when (doneOutcome) {
            DoneOutcome.Next -> moveNextStep()
            DoneOutcome.Finish -> finishDispense()
            DoneOutcome.ConfirmMismatch -> {
                _uiState.update { it.copy(showCountMismatchDialog = true) }
                engine.pauseDetection()
            }
            is DoneOutcome.Error -> showToast(doneOutcome.text)
            DoneOutcome.Ignored -> Unit
        }
    }

    /**
     * Tour-only escape hatch for "count_add_circle"'s dummy "Next": seeds a manual count row
     * since Done checks the real DB-backed [DispenseCountUiState.stepCounts]; loops since
     * one tap must clear every chained [StepState] sharing this circle, not just the current one.
     */
    fun dummyCompleteCount() {
        // Hard guard, not just an unreachable-UI assumption: this seeds fabricated
        // DB rows, so it must never run outside an active tutorial regardless of
        // what calls it.
        if (!FtueController.state.isActive) return
        FtueController.track(viewModelScope.launch {
            // The txn pointer is global and an HL7 order may have set it: never write a demo count to a real txn.
            if (!dispenseCountRepository.isDemoTxn(preferenceHelper.getTxnId())) return@launch
            val countingCircleSteps = setOf(
                StepState.CONTAINER_INITIATE,
                StepState.TARGET_VERIFICATION,
                StepState.TARGET_REVERIFICATION,
            )
            var guard = 0
            while (_uiState.value.currentStep in countingCircleSteps && guard < countingCircleSteps.size + 1) {
                guard++
                val stepType = _uiState.value.currentStep
                val shortfall = _uiState.value.targetCount - _uiState.value.totalCount
                if (shortfall > 0) {
                    val newCountId = dispenseCountRepository.addManualCount(preferenceHelper.getTxnId(), stepType, shortfall)
                    // Update uiState synchronously here rather than waiting on the DB's
                    // reactive Flow observer (observeStepCounts) to catch up — the Done
                    // below reads _uiState.value.stepCounts immediately.
                    _uiState.update { state ->
                        state.copy(
                            stepCounts = state.stepCounts + TxnDetail(
                                txnDetailId = newCountId,
                                count = shortfall,
                                createdAt = System.currentTimeMillis(),
                                image = null,
                                type = stepType,
                            )
                        )
                    }
                }
                val before = _uiState.value.currentStep
                onDoneTapped()
                // moveNextStep() assigns currentStep synchronously (not suspending), so this
                // is already up to date for the loop condition above — bail out if it somehow
                // didn't move, rather than spinning on a step our shortfall calc didn't satisfy.
                if (_uiState.value.currentStep == before) break
            }
        })
    }

    private fun finishDispense() {
        _uiState.update { it.copy(showCountMismatchDialog = false) }
        viewModelScope.launch {
            try {
                val total = dispenseCountRepository.totalPillCount(preferenceHelper.getTxnId())
                val state = _uiState.value
                val finishOutcome = DispenseWorkflow.finish(
                    totalPillCount = total,
                    notesDialogOn = preferenceHelper.getShowNotesDialogSetting(),
                    step = state.currentStep,
                    stepTarget = state.targetCount,
                    countedTotal = state.totalCount,
                )
                when (finishOutcome) {
                    FinishOutcome.NoCounts -> showToast(R.string.no_transaction_found)
                    FinishOutcome.AskNote -> _uiState.update { it.copy(showNotesDialog = true) }
                    FinishOutcome.Complete -> completeDispense()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("Failed to finalize Done for current step", e, event = LogEvent.PILL_COUNT_FAILED)
                showGenericError()
            }
        }
    }

    private fun onNoteSaved(note: String) {
        _uiState.update { it.copy(showNotesDialog = false) }
        viewModelScope.launch {
            try {
                dispenseCountRepository.saveNote(preferenceHelper.getTxnId(), note)
                completeDispense()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("Failed to save note and confirm done", e, event = LogEvent.PILL_COUNT_FAILED)
                showGenericError()
            }
        }
    }

    private fun completeDispense() {
        confirmDoneStarted = true
        FtueController.track(viewModelScope.launch {
            try {
                val status = dispenseCountRepository.complete(preferenceHelper.getTxnId()) ?: return@launch
                _uiState.update { it.copy(capturedStill = null) }
                // The screen goes back to the queue or the Dashboard; the next count starts without gloves.
                engine.resetGloves()
                _effects.send(DispenseCountEffect.Finished)
                logger.i("Transaction completed. Status=$status")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("Failed to confirm/complete transaction", e, event = LogEvent.PILL_COUNT_FAILED)
                showGenericError()
            }
        })
    }

    private fun deleteCount(countId: Long) {
        viewModelScope.launch {
            try {
                dispenseCountRepository.deleteCount(preferenceHelper.getTxnId(), countId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("Failed to delete transaction detail. Id=$countId", e, event = LogEvent.PILL_COUNT_FAILED)
            }
        }
    }

    private fun onTargetCountEntered(targetCount: Int) {
        _uiState.update { it.copy(targetCount = targetCount, showTargetCountDialog = false) }
        viewModelScope.launch {
            try {
                dispenseCountRepository.saveTargetCount(preferenceHelper.getTxnId(), targetCount)
                logger.i("Target count updated to $targetCount")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("Failed to update target count to $targetCount", e, event = LogEvent.PILL_COUNT_FAILED)
            }
        }
    }

    // ─────────────────────────── Bottle rescans ───────────────────────────

    /**
     * Always-on rescan hook for the dispense pill-counting steps (TARGET_VERIFICATION /
     * TARGET_REVERIFICATION). Fires on every barcode the camera decodes while the
     * pharmacist is actively counting pills for a dispense txn, so a second bottle of the same
     * drug can be detected mid-count.
     *
     * Behavior (bottle info is always read fresh from Room — no in-memory cache — so a
     * killed/resumed process still compares against the real persisted state):
     * - Ignored if not currently on a dispense counting step, or the decoded NDC doesn't match
     *   the active txn's NDC (wrong-drug scans are dropped silently, no lookup/popup).
     * - A non-GS1 barcode carries no lot/exp/serial: treated as a count-only bottle entry.
     * - If the decoded lot+exp+serial exactly match the last bottle on file → toast only.
     * - Else if pills have already been counted since the last bottle was recorded → show the
     *   "add new bottle" confirm dialog (adds a new bottle entry on confirm).
     * - Else (nothing counted yet against the last bottle) → show the "replace bottle" confirm
     *   dialog (overwrites the last bottle's lot/exp/serial on confirm).
     */
    private fun onBottleScanned(rawValue: String, imagePath: String?) {
        val countingSteps = setOf(StepState.TARGET_VERIFICATION, StepState.TARGET_REVERIFICATION)
        if (_uiState.value.currentStep !in countingSteps) return
        if (loadedTxn == null) return
        if (isProcessingBottleScan) return
        isProcessingBottleScan = true

        viewModelScope.launch {
            try {
                val txnId = preferenceHelper.getTxnId()
                val activeNdc = loadedTxn?.ndc
                if (activeNdc.isNullOrBlank()) return@launch

                val scan = scanDecoder.decode(rawValue)

                val scannedDrug = scan.gtin14?.let { dispenseCountRepository.findLocalDrug(it) }
                if (scannedDrug == null || scannedDrug.ndc != activeNdc) {
                    // Not the same drug (or unreadable) — ignore, no popup, no lookup, no DB write.
                    return@launch
                }

                val bottles = dispenseCountRepository.loadBottles(txnId) ?: return@launch
                val scannedBottle = DispenseBottle(
                    lotNumber = scan.lot,
                    expirationDate = scan.expiry,
                    serialNumber = scan.serial,
                    txnId = txnId,
                    barcodeImagePath = imagePath,
                )
                val countedSoFar = dispenseCountRepository.totalPillCount(txnId)
                when (BottleRescanDecider.decide(bottles, scannedBottle, scan.isGs1, countedSoFar)) {
                    RescanOutcome.AlreadyScanned -> showToast(R.string.bottle_already_scanned)
                    RescanOutcome.AskAddNew -> {
                        pendingBottleScan = scannedBottle
                        _uiState.update { it.copy(showAddBottleDialog = true) }
                    }
                    RescanOutcome.AskReplace -> {
                        pendingBottleScan = scannedBottle
                        _uiState.update { it.copy(showReplaceBottleDialog = true) }
                    }
                }
            } catch (e: Exception) {
                logger.e("BOTTLE_SCAN onBottleScanned failed", e, event = LogEvent.NDC_SCAN_FAILED)
            } finally {
                isProcessingBottleScan = false
            }
        }
    }

    private fun onAddBottleConfirmed() {
        val pending = pendingBottleScan
        _uiState.update { it.copy(showAddBottleDialog = false) }
        if (pending == null) return
        viewModelScope.launch {
            try {
                dispenseCountRepository.addBottle(preferenceHelper.getTxnId(), pending)
                pendingBottleScan = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("BOTTLE_SCAN failed to add new bottle", e, event = LogEvent.NDC_SCAN_FAILED)
            }
        }
    }

    private fun onReplaceBottleConfirmed() {
        val pending = pendingBottleScan
        _uiState.update { it.copy(showReplaceBottleDialog = false) }
        if (pending == null) return
        viewModelScope.launch {
            try {
                dispenseCountRepository.replaceLastBottle(preferenceHelper.getTxnId(), pending)
                pendingBottleScan = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("BOTTLE_SCAN failed to replace last bottle", e, event = LogEvent.NDC_SCAN_FAILED)
            }
        }
    }

    private fun dismissBottleDialog(isAddDialog: Boolean) {
        pendingBottleScan = null
        _uiState.update {
            if (isAddDialog) it.copy(showAddBottleDialog = false) else it.copy(showReplaceBottleDialog = false)
        }
    }

    // ─────────────────────────── Vial photo ───────────────────────────

    /**
     * True when VIAL is the final step of the active workflow. The auto-capture
     * path uses this to decide whether scanning the vial should finish the flow
     * outright (complete the transaction, or show the notes prompt when that
     * setting is on) or merely capture the still and wait for the user to tap Done
     * before advancing to the remaining step(s).
     */
    private fun isVialLastStep(): Boolean = _uiState.value.steps.lastOrNull() == StepState.VIAL

    /**
     * Capture the VIAL still photo.
     *
     * @param autoConfirm when true (auto-capture path, where the vial's RX matched
     *   the active transaction), immediately commit the photo as if the user tapped
     *   "Done" once the bitmap lands — this advances the workflow and completes the
     *   transaction. When false (manual capture), the still is shown and the user
     *   confirms via the Done button.
     *
     * A second call while a capture is still in flight is a no-op: no sound, no
     * flash, no capture request. See [DispenseCountUiState.isCapturing].
     */
    private fun captureVialStill(autoConfirm: Boolean) {
        if (_uiState.value.isCapturing) return
        _uiState.update { it.copy(isCapturing = true) }

        val captureStarted = engine.captureStill(
            onCaptured = { vialStill ->
                _uiState.update { it.copy(isCapturing = false) }
                // A capture that lands after the user already left VIAL would
                // re-show the still overlay on top of the next step.
                if (_uiState.value.currentStep == StepState.VIAL) {
                    _uiState.update { it.copy(capturedStill = vialStill) }
                    // A fresh still is unsaved, so the flag can never outlive the still it guards.
                    captureCommitted = false
                    if (autoConfirm) commitVialStill(vialStill)
                }
            },
            onError = { _uiState.update { it.copy(isCapturing = false) } }
        )
        if (!captureStarted) {
            _uiState.update { it.copy(isCapturing = false) }
            return
        }

        SoundUtils.playCaptureSound()
        viewModelScope.launch {
            _uiState.update { it.copy(showFlash = true) }
            delay(350)
            _uiState.update { it.copy(showFlash = false) }
        }
    }

    private fun redoVialStill() {
        _uiState.update { it.copy(capturedStill = null) }
        captureCommitted = false
    }

    private fun commitVialStill(vialStill: Bitmap) {
        // Guard only the photo write. finishDispense has exits that leave the user on VIAL
        // (notes dialog dismissed, total 0), so a Done re-tap must still advance the flow.
        if (!captureCommitted) {
            captureCommitted = true
            val grayscale = engine.isGrayscale()
            FtueController.track(viewModelScope.launch {
                try {
                    // The tour's grey placeholder isn't worth a file an interruption could orphan.
                    dispenseCountRepository.saveVialPhoto(
                        preferenceHelper.getTxnId(), vialStill, grayscale,
                        saveFile = !FtueController.state.isActive,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.e("Failed to save vial photo transaction detail", e, event = LogEvent.VIAL_SCAN_FAILED)
                }
            })
        }
        advanceFromVial()
        // The still is left up here on purpose. Whichever way advanceFromVial goes it
        // clears the still itself — redoVialStill on the CONTAINER_PENDING entry,
        // completeDispense on completion — so clearing it here only flashes the live
        // camera underneath.
        engine.resumeLiveCounting()
    }

    /**
     * Advance past VIAL, or offer to skip the back count.
     *
     * CONTAINER_PENDING re-counts what is left in the stock bottle. When the bottle was
     * poured out and fully dispensed there is nothing left, so the step is offered as a
     * skip instead of being walked into with a target of 0. The CONTAINER_INITIATE total
     * is read from the repository because uiState only ever holds the current step's rows.
     */
    private fun advanceFromVial() {
        viewModelScope.launch {
            try {
                val poured = dispenseCountRepository.stepPillTotal(
                    preferenceHelper.getTxnId(), StepState.CONTAINER_INITIATE
                )
                if (DispenseWorkflow.offerSkipBackCount(_uiState.value.steps, poured, loadedTxn?.targetCount ?: 0)) {
                    if (FtueController.state.isActive) {
                        // Tutorial: this dialog is real business UI the tour doesn't
                        // gate/explain — an extra manual tap here interrupts the guided
                        // flow for no reason, since skipping the back-count step is
                        // already the only sensible outcome (there's nothing left to
                        // scan). Take it automatically instead, same as tapping SKIP
                        // would, and let the tour continue uninterrupted.
                        finishDispense()
                    } else {
                        // No camera frames arrive while the dialog sits on the vial still, so
                        // the watchdog would fire and drop the idle overlay behind it.
                        engine.stopIdleTimer()
                        _uiState.update { it.copy(showSkipStepDialog = true) }
                    }
                } else {
                    moveNextStep()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("Failed to advance from VIAL step", e, event = LogEvent.VIAL_SCAN_FAILED)
            }
        }
    }

    private fun loadExistingVialPhoto(txnId: Long) {
        viewModelScope.launch {
            val vialStill = dispenseCountRepository.loadVialPhoto(txnId) ?: return@launch
            _uiState.update { it.copy(capturedStill = vialStill) }
            // Re-entering VIAL with an existing photo must leave Done usable.
            captureCommitted = false
        }
    }

    /** Tour: saves a blank demo vial photo (no camera needed); returns once the workflow has left VIAL, else throws. */
    suspend fun captureVialForTour() {
        if (!FtueController.state.isActive) return
        check(dispenseCountRepository.isDemoTxn(preferenceHelper.getTxnId())) { "Tour vial capture on a non-demo txn" }
        confirmDoneStarted = false
        if (_uiState.value.currentStep == StepState.VIAL && !captureCommitted) {
            val demoStill = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.DKGRAY) }
            _uiState.update { it.copy(capturedStill = demoStill) }
            commitVialStill(demoStill)
        }
        // VIAL stays the current step until the note is answered (notes on) or the txn is
        // confirmed (notes off), so either of those is as good as leaving VIAL.
        withTimeout(TourActionTimeoutMs) {
            while (_uiState.value.currentStep == StepState.VIAL && !_uiState.value.showNotesDialog && !confirmDoneStarted) delay(50)
        }
    }

    // ─────────────────────────── Reset ───────────────────────────

    /**
     * Throws away everything collected for the active count so it can restart at the
     * container scan, then lands on the SCAN step. Hard deletes only — nothing here is
     * recoverable. Not an action: the flow view model runs it before it flips the stage.
     *
     * Runs on viewModelScope rather than the caller's: leaving the screen mid-reset must
     * not strand a txn whose details are deleted but whose flags are still set. The caller
     * joins, so the stage flip still happens after the delete.
     *
     * @return false when the reset failed (an error toast is shown); the caller must not move on.
     */
    suspend fun resetForRecount(): Boolean {
        var ok = false
        viewModelScope.launch {
            try {
                performReset()
                ok = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("Failed to reset transaction", e, event = LogEvent.PILL_COUNT_RESET_FAILED)
                showGenericError()
            }
        }.join()
        if (ok) enterScanStep()
        return ok
    }

    private suspend fun performReset() {
        val txnId = preferenceHelper.getTxnId()
        if (txnId != 0L) {
            dispenseCountRepository.reset(txnId)
        }

        keepLoadedTxn(null)
        engine.clearDetectedPills()
        _uiState.update {
            it.copy(
                capturedStill = null,
                stepCounts = emptyList(),
            )
        }
        logger.i("Transaction reset. txnId=$txnId")
    }

    override fun onCleared() {
        super.onCleared()
        engine.close()
    }
}
