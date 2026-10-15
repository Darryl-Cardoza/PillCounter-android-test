package com.dispensesure.retail.feature.stockCount.loose

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dispensesure.retail.R
import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.models.isControlledDrugType
import com.dispensesure.retail.core.room.models.DrugMasterEntity
import com.dispensesure.retail.core.scanning.data.DrugResolver
import com.dispensesure.retail.core.scanning.domain.model.DecodedScan
import com.dispensesure.retail.core.scanning.domain.model.DrugLookup
import com.dispensesure.retail.core.scanning.engine.CaptureResult
import com.dispensesure.retail.core.scanning.engine.EngineEvent
import com.dispensesure.retail.core.scanning.engine.EngineState
import com.dispensesure.retail.core.scanning.engine.PhotoLabel
import com.dispensesure.retail.core.scanning.engine.PillCountingEngine
import com.dispensesure.retail.core.scanning.engine.toastText
import com.dispensesure.retail.core.scanning.logic.ScanDecoder
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import com.dispensesure.retail.feature.stockCount.data.StockCountRepository
import com.dispensesure.retail.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import javax.inject.Inject

/**
 * Loose-pill count for a stock batch. Scan the container, ADD pills into an in-memory list,
 * then save the total as one stock line on Proceed → Yes. Leaving drops the list.
 */
@HiltViewModel
class LooseCountViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val scanDecoder: ScanDecoder,
    private val drugResolver: DrugResolver,
    private val stockCountRepository: StockCountRepository,
    preferenceHelper: PreferenceHelper,
    engineFactory: PillCountingEngine.Factory,
) : ViewModel() {

    private val logger = AppLogger("LooseCountVM")

    // Route values: the batch to count into (0 = saving creates one), the bucket the user
    // picked, and for a PMS batch the NDCs its request allows (empty = any).
    private val batchId: Long = savedStateHandle[Screen.LooseCount.ARG_BATCH_ID] ?: 0L
    private val bucketId: String? =
        savedStateHandle.get<String>(Screen.LooseCount.ARG_BUCKET_ID)?.ifBlank { null }
    private val allowedNdcs: Set<String> = savedStateHandle.get<String>(Screen.LooseCount.ARG_ALLOWED_NDCS)
        .orEmpty()
        .split(",")
        .map { allowedNdc -> allowedNdc.trim() }
        .filter { allowedNdc -> allowedNdc.isNotBlank() }
        .toSet()

    // Camera, ML, idle, gloves, tray and the Add photo. Declared before anything that reads it.
    private val engine = engineFactory.create(viewModelScope)

    private val _uiState = MutableStateFlow(
        LooseCountUiState(bucketId = bucketId, isVoiceOverEnabled = preferenceHelper.isSoundOverride())
    )
    val uiState: StateFlow<LooseCountUiState> = _uiState.asStateFlow()

    private val _effects = Channel<LooseCountEffect>(Channel.BUFFERED)
    val effects: Flow<LooseCountEffect> = _effects.receiveAsFlow()

    /** The engine's live camera state, for the overlay, the circle and the idle scrim. */
    val engineState: StateFlow<EngineState> = engine.state

    val recentDetectionCounts: StateFlow<ArrayDeque<Int>> = engine.lastTenDetections

    val addPops: Flow<Int> = engine.addPops

    // Ids for the in-memory entries. Never reused, so a View All delete always hits its row.
    private var nextEntryId = 1L

    init {
        engine.resetGloves()
        viewModelScope.launch { engine.events.collect { engineEvent -> onEngineEvent(engineEvent) } }
        viewModelScope.launch {
            stockCountRepository.observeCanReset(batchId).collect { canReset ->
                _uiState.update { state -> state.copy(canReset = canReset) }
            }
        }
    }

    /** True when the backend's colour-image setting is off; the barcode analyzer saves grayscale then. */
    fun isGrayscaleImage(): Boolean = engine.isGrayscale()

    fun onAction(action: LooseCountAction) {
        when (action) {
            is LooseCountAction.ContainerScanned -> onContainerScanned(action.rawValue)
            is LooseCountAction.AddTapped -> onAddTapped(action.detectedCount)
            is LooseCountAction.EntryDeleted -> deleteEntry(action.entryId)
            LooseCountAction.DoneTapped -> onDoneTapped()
            LooseCountAction.EndCountConfirmed -> saveCount()
            LooseCountAction.EndCountDismissed -> {
                engine.resumeFromIdle()
                _uiState.update { state -> state.copy(showEndCountDialog = false) }
            }
            LooseCountAction.ResetConfirmed -> resetCount()
            LooseCountAction.ResumeFromIdleTapped -> engine.resumeFromIdle()
            is LooseCountAction.TrayClassified -> engine.classifyTray(action.color, action.isHazardous)
            is LooseCountAction.HistoryVisibilityChanged -> onHistoryVisibilityChanged(action.isShown)
            LooseCountAction.ScreenTouched -> engine.noteActivity()
            LooseCountAction.CountSteadied -> engine.playCountSoundIfEnabled()
            is LooseCountAction.CameraHelperReady -> engine.attachCamera(action.cameraHelper)
            LooseCountAction.PreviewSizeKnown -> engine.start()
            is LooseCountAction.FrameArrived -> engine.onFrame(action.image)
            is LooseCountAction.FilteredPillsDrawn -> engine.onFilteredPills(action.filteredPills)
        }
    }

    // ─────────────────────────── Container scan ───────────────────────────

    private fun onContainerScanned(rawValue: String) {
        val state = _uiState.value
        if (state.stage != LooseCountStage.SCAN_CONTAINER || state.isResolvingContainer) return
        // An Rx label ({RXNO}|{NDCNO}|…) is never a container barcode.
        if (rawValue.contains('|')) {
            rejectContainer(R.string.scan_correct_label)
            return
        }
        val decodedScan = scanDecoder.decode(rawValue)
        val gtin14 = decodedScan.gtin14
            ?.takeIf { scannedGtin -> scannedGtin.length == 14 && scannedGtin.all { digit -> digit.isDigit() } }
        if (gtin14 == null) {
            rejectContainer(toastText = null)
            return
        }
        _uiState.update { it.copy(isResolvingContainer = true) }
        // No pill detection while the lookup runs, as on the dispense container scan.
        engine.pauseDetection()
        viewModelScope.launch {
            val drugLookup = drugResolver.resolve(
                code = gtin14,
                nameIfMissing = DrugResolver.UNKNOWN_DRUG_NAME,
                scannedGtin = gtin14,
            )
            engine.resumeDetection()
            _uiState.update { it.copy(isResolvingContainer = false) }
            applyContainerLookup(drugLookup, gtin14, decodedScan)
        }
    }

    private fun applyContainerLookup(drugLookup: DrugLookup, gtin14: String, decodedScan: DecodedScan) {
        val scannedDrug = (drugLookup as? DrugLookup.Found)?.drug
        when {
            // A PMS batch counts only what its request asked for; an unknown drug can't be one.
            allowedNdcs.isNotEmpty() && (scannedDrug == null || scannedDrug.ndc !in allowedNdcs) ->
                rejectContainer(R.string.batch_stock_count_ndc_not_in_request, scannedDrug?.ndc ?: gtin14)
            drugLookup is DrugLookup.Failed -> rejectContainer(R.string.batch_stock_count_scan_failed)
            scannedDrug == null -> rejectContainer(R.string.the_scanned_ndc_does_not_match)
            else -> startCounting(scannedDrug, decodedScan)
        }
    }

    private fun rejectContainer(@StringRes toastText: Int?, formatArg: String? = null) {
        _effects.trySend(LooseCountEffect.ContainerRejected(toastText, formatArg))
    }

    private fun startCounting(scannedDrug: DrugMasterEntity, decodedScan: DecodedScan) {
        _uiState.update {
            it.copy(
                stage = LooseCountStage.COUNTING,
                drug = scannedDrug,
                lotNumber = decodedScan.lot,
                expirationDate = decodedScan.expiry,
                serialNumber = decodedScan.serial,
            )
        }
        engine.configureHazardous(scannedDrug.isHazardous)
        // Gloves are only checked for a hazardous drug.
        if (scannedDrug.isHazardous) engine.loadGloveModel()
    }

    // ─────────────────────────── ADD and View All ───────────────────────────

    private fun onAddTapped(detectedCount: Int) {
        val countedDrug = _uiState.value.drug ?: return
        val capturedAdd = when (val captureResult = engine.captureCount(detectedCount)) {
            is CaptureResult.Rejected -> {
                _effects.trySend(LooseCountEffect.ShowToast(captureResult.reason.toastText()))
                return
            }
            is CaptureResult.Captured -> captureResult
        }
        val photoLabel = countPhotoLabel(countedDrug)
        viewModelScope.launch {
            // No raw crop: only the dispense count sends one to the PMS.
            val savedPhoto = engine.saveCountPhoto(capturedAdd, photoLabel, keepRawCrop = false)
            val countedEntry = LooseCountEntry(
                entryId = nextEntryId++,
                pillCount = detectedCount,
                imagePath = savedPhoto.imagePath,
                createdAt = System.currentTimeMillis(),
            )
            _uiState.update { it.copy(entries = it.entries + countedEntry) }
        }
    }

    /** Drug and scanned-bottle text for the ADD photo strip. A stock count has no Rx. */
    private fun countPhotoLabel(countedDrug: DrugMasterEntity): PhotoLabel {
        val state = _uiState.value
        return PhotoLabel(
            drugName = countedDrug.drugName,
            drugImagePath = countedDrug.drugImagePath,
            isStockCount = true,
            requestedNdc = countedDrug.ndc,
            dispensedNdc = countedDrug.ndc,
            lotNumber = state.lotNumber,
            serialNumber = state.serialNumber,
            expirationDate = state.expirationDate,
            rxRefill = null,
            stepLabel = StepState.TARGET_VERIFICATION.name,
        )
    }

    private fun deleteEntry(entryId: Long) {
        val deletedEntry = _uiState.value.entries.firstOrNull { entry -> entry.entryId == entryId } ?: return
        _uiState.update { it.copy(entries = it.entries - deletedEntry) }
        stockCountRepository.deletePhotos(listOfNotNull(deletedEntry.imagePath))
    }

    // ─────────────────────────── Done, save, reset ───────────────────────────

    private fun onDoneTapped() {
        if (_uiState.value.totalCount == 0) {
            _effects.trySend(LooseCountEffect.ShowToast(R.string.no_transaction_found))
            return
        }
        _uiState.update { it.copy(showEndCountDialog = true) }
    }

    private fun saveCount() {
        val state = _uiState.value
        val countedDrug = state.drug ?: return
        if (state.isSaving) return
        _uiState.update { it.copy(showEndCountDialog = false, isSaving = true) }
        val savedEntries = state.entries
        val photoPaths = savedEntries.mapNotNull { entry -> entry.imagePath }
        // Controlled drugs (CII–CVI) keep their photos on the saved line; others are deleted.
        val isControlled = isControlledDrugType(countedDrug.drugType)
        viewModelScope.launch {
            try {
                val savedBatchId = stockCountRepository.commitLooseCount(
                    batchId = batchId,
                    bucketId = bucketId,
                    drugId = countedDrug.drugId,
                    lotNumber = state.lotNumber,
                    expirationDate = state.expirationDate,
                    serialNumber = state.serialNumber,
                    loosePills = state.totalCount,
                    controlledImagePaths = if (isControlled) photoPaths.takeIf { it.isNotEmpty() } else null,
                )
                if (!isControlled) stockCountRepository.deletePhotos(photoPaths)
                // An ADD that landed while saving stays, so leaving still deletes its photo.
                _uiState.update { it.copy(entries = it.entries - savedEntries.toSet(), isSaving = false) }
                _effects.send(LooseCountEffect.CountSaved(savedBatchId))
            } catch (error: Exception) {
                // Rolled back: the list stays so Proceed can be tried again.
                logger.e("Loose count save failed; counts kept for a retry", error)
                _uiState.update { it.copy(isSaving = false) }
                _effects.send(LooseCountEffect.ShowToast(R.string.batch_stock_count_save_failed))
            }
        }
    }

    private fun resetCount() {
        stockCountRepository.deletePhotos(_uiState.value.entries.mapNotNull { entry -> entry.imagePath })
        _uiState.update {
            it.copy(
                stage = LooseCountStage.SCAN_CONTAINER,
                drug = null,
                lotNumber = null,
                expirationDate = null,
                serialNumber = null,
                entries = emptyList(),
            )
        }
        engine.clearDetectedPills()
    }

    // ─────────────────────────── Engine ───────────────────────────

    // View All hides the camera: no idle pause and no detection behind it.
    private fun onHistoryVisibilityChanged(isShown: Boolean) {
        if (isShown) engine.stopIdleTimer() else engine.armIdleTimer()
        engine.setCameraPaused(isShown)
    }

    // Gloves and tray results are saved for dispense only; a stock count just shows tray warnings.
    private fun onEngineEvent(engineEvent: EngineEvent) {
        if (engineEvent is EngineEvent.Warning) _effects.trySend(LooseCountEffect.ShowToast(engineEvent.text))
    }

    override fun onCleared() {
        super.onCleared()
        // Leaving without saving: the photos have no line to belong to.
        stockCountRepository.deletePhotos(_uiState.value.entries.mapNotNull { entry -> entry.imagePath })
        engine.close()
    }
}
