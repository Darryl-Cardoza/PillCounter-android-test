package com.dispensesure.retail.feature.dispense.domain.model

import com.dispensesure.retail.core.scanning.domain.model.DispenseBottle
import com.dispensesure.retail.feature.dashboard.domain.model.KpiFilter

/** Everything the dispense screen tells the flow view model. */
sealed interface DispenseFlowAction {
    // ── Scans, as DispenseBarcodeRouting routes them ──
    data class RxLabelScanned(val rawValue: String) : DispenseFlowAction
    data class ContainerScanned(val scannedCode: String, val firstBottle: DispenseBottle) : DispenseFlowAction
    data object RxLabelScannedOnContainerStep : DispenseFlowAction
    data class VialLabelScanned(val rawValue: String) : DispenseFlowAction

    // ── Rx sheet and dialogs ──
    data object RxProceedTapped : DispenseFlowAction
    data object RxCancelled : DispenseFlowAction
    data object SubstituteConfirmed : DispenseFlowAction
    data object SubstituteDismissed : DispenseFlowAction
    data object OnHoldDismissed : DispenseFlowAction

    // ── Queue ──
    data class QueueFilterSelected(val filter: KpiFilter?) : DispenseFlowAction
    data class QueueItemTapped(val txnId: Long) : DispenseFlowAction
    data object LeavingForQueue : DispenseFlowAction
    // The count view model finished the dispense.
    data object CountFinished : DispenseFlowAction

    // Reset confirmed. resetCount (the count view model's reset) runs first, on this view model's scope;
    // false means it failed and the stage stays.
    data class ResetConfirmed(val resetCount: suspend () -> Boolean) : DispenseFlowAction

    // ── Tour "Next" on the scan steps: demo data instead of a real label ──
    data class TourRxNextTapped(val demoDrugName: String) : DispenseFlowAction
    data object TourContainerNextTapped : DispenseFlowAction
}
