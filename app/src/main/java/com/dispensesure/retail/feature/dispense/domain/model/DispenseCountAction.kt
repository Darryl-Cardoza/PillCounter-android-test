package com.dispensesure.retail.feature.dispense.domain.model

import androidx.camera.core.ImageProxy
import com.dispensesure.retail.core.scanning.domain.model.DetectedPill
import com.dispensesure.retail.core.scanning.logic.CameraHelper
import com.dispensesure.retail.core.scanning.logic.TrayColor

/** Everything the dispense screen tells the count view model. */
sealed interface DispenseCountAction {
    // ── Stage and camera ──
    data class StageChanged(val stage: DispenseStage, val txnId: Long, val isHazardousDrug: Boolean) : DispenseCountAction
    // A verification sheet, dialog or loader of the flow is over the camera.
    data class FlowOverlayChanged(val isShown: Boolean) : DispenseCountAction
    data class HistoryVisibilityChanged(val isShown: Boolean) : DispenseCountAction
    data class LeavingForQueue(val resetGloves: Boolean) : DispenseCountAction
    data object ScreenTouched : DispenseCountAction
    data object CountSteadied : DispenseCountAction
    data class CameraHelperReady(val cameraHelper: CameraHelper) : DispenseCountAction
    data object PreviewSizeKnown : DispenseCountAction
    data class FrameArrived(val image: ImageProxy) : DispenseCountAction
    data class FilteredPillsDrawn(val filteredPills: List<DetectedPill>) : DispenseCountAction
    data object ResumeFromIdleTapped : DispenseCountAction
    data class TrayClassified(val color: TrayColor, val isHazardous: Boolean) : DispenseCountAction

    // ── Counting ──
    data class AddTapped(val detectedCount: Int) : DispenseCountAction
    data object DoneTapped : DispenseCountAction
    data class CountDeleted(val countId: Long) : DispenseCountAction
    data class BottleScanned(val rawValue: String, val imagePath: String?) : DispenseCountAction

    // ── Vial photo ──
    data object VialCaptureTapped : DispenseCountAction
    // The vial label's Rx matched the txn: capture on its own.
    data object VialLabelMatched : DispenseCountAction
    data object VialRedoTapped : DispenseCountAction
    data object VialSaveTapped : DispenseCountAction

    // ── Dialogs ──
    data class TargetCountEntered(val targetCount: Int) : DispenseCountAction
    data object TargetCountDismissed : DispenseCountAction
    data object NoteDismissed : DispenseCountAction
    data object NoteSkipped : DispenseCountAction
    data class NoteSaved(val note: String) : DispenseCountAction
    data object CountMismatchConfirmed : DispenseCountAction
    data object CountMismatchDismissed : DispenseCountAction
    data object AddBottleConfirmed : DispenseCountAction
    data object AddBottleDismissed : DispenseCountAction
    data object ReplaceBottleConfirmed : DispenseCountAction
    data object ReplaceBottleDismissed : DispenseCountAction
    data object SkipBackCountTapped : DispenseCountAction
}
