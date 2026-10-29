package com.dispensesure.retail.feature.stockCount.loose

import androidx.annotation.StringRes
import androidx.camera.core.ImageProxy
import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.room.models.DrugMasterEntity
import com.dispensesure.retail.core.scanning.domain.model.DetectedPill
import com.dispensesure.retail.core.scanning.logic.CameraHelper
import com.dispensesure.retail.core.scanning.logic.TrayColor

/** The container scan, then the pill count. */
enum class LooseCountStage { SCAN_CONTAINER, COUNTING }

/** The strip's steps: the container scan, then one counting step ("Scan Open Pills"). */
val LOOSE_COUNT_STEPS: List<StepState> = listOf(StepState.SCAN, StepState.TARGET_VERIFICATION)

/** One ADD, held in memory until the count is saved. */
data class LooseCountEntry(
    val entryId: Long,
    val pillCount: Int,
    val imagePath: String?,
    val createdAt: Long,
)

data class LooseCountUiState(
    val stage: LooseCountStage = LooseCountStage.SCAN_CONTAINER,
    // A scanned container is being looked up; scanning waits and the loader shows.
    val isResolvingContainer: Boolean = false,
    val drug: DrugMasterEntity? = null,
    // The bucket picked on the sealed-bottle screen; null shows the default bucket.
    val bucketId: String? = null,
    // GS1 values from the container scan, stamped on the photos and the saved line.
    val lotNumber: String? = null,
    val expirationDate: String? = null,
    val serialNumber: String? = null,
    val entries: List<LooseCountEntry> = emptyList(),
    val showEndCountDialog: Boolean = false,
    val isSaving: Boolean = false,
    // False once the batch has reached the pharmacy system.
    val canReset: Boolean = true,
    val isVoiceOverEnabled: Boolean = false,
) {
    val totalCount: Int
        get() = entries.sumOf { entry -> entry.pillCount }

    val currentStep: StepState
        get() = if (stage == LooseCountStage.SCAN_CONTAINER) StepState.SCAN else StepState.TARGET_VERIFICATION
}

/** Everything the loose-count screen tells its view model. */
sealed interface LooseCountAction {
    data class ContainerScanned(val rawValue: String) : LooseCountAction
    data class AddTapped(val detectedCount: Int) : LooseCountAction
    data class EntryDeleted(val entryId: Long) : LooseCountAction
    data object DoneTapped : LooseCountAction
    data object EndCountConfirmed : LooseCountAction
    data object EndCountDismissed : LooseCountAction
    data object ResetConfirmed : LooseCountAction
    data object ResumeFromIdleTapped : LooseCountAction
    data class TrayClassified(val color: TrayColor, val isHazardous: Boolean) : LooseCountAction
    data class HistoryVisibilityChanged(val isShown: Boolean) : LooseCountAction
    data object ScreenTouched : LooseCountAction
    data object CountSteadied : LooseCountAction
    data class CameraHelperReady(val cameraHelper: CameraHelper) : LooseCountAction
    data object PreviewSizeKnown : LooseCountAction
    data class FrameArrived(val image: ImageProxy) : LooseCountAction
    data class FilteredPillsDrawn(val filteredPills: List<DetectedPill>) : LooseCountAction
}

/** One-off things the screen does for its view model. */
sealed interface LooseCountEffect {
    data class ShowToast(@StringRes val text: Int, val formatArg: String? = null) : LooseCountEffect

    // A container scan was turned away. No toast: read again at once; a toast: after a pause.
    data class ContainerRejected(@StringRes val toastText: Int?, val formatArg: String? = null) : LooseCountEffect

    // Saved: hand the batch id back to the sealed-bottle screen and leave.
    data class CountSaved(val batchId: Long) : LooseCountEffect
}
