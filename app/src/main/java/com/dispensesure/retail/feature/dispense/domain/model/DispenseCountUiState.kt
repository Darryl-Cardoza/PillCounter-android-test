package com.dispensesure.retail.feature.dispense.domain.model

import android.graphics.Bitmap
import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.scanning.domain.model.TxnDetail

/**
 * Represents the UI state for the pill counting process.
 *
 * Camera and detection state is not here: the screen reads the engine's own state.
 */
data class DispenseCountUiState(
    val drugName: String = "",
    val ndc: String = "",
    val strength: String = "",
    val dosageForm: String = "",
    // Null shows the default bucket.
    val bucketId: String? = null,
    val drugImage: String = "",
    // The step's target. On the back count, the pills that should be left in the bottle.
    val targetCount: Int = 0,
    // The current step's counts, newest first.
    val stepCounts: List<TxnDetail> = emptyList(),
    val currentStep: StepState = StepState.TARGET_VERIFICATION,
    val steps: List<StepState> = emptyList(),
    // True once the dispense txn is loaded for counting.
    val isTxnLoaded: Boolean = false,
    // False once the txn has reached the pharmacy system.
    val canReset: Boolean = false,
    val isVoiceOverEnabled: Boolean = false,
    // VIAL step: the still on screen, the shutter flash, and a capture in flight.
    val capturedStill: Bitmap? = null,
    val showFlash: Boolean = false,
    val isCapturing: Boolean = false,
    val showTargetCountDialog: Boolean = false,
    val showNotesDialog: Boolean = false,
    val showCountMismatchDialog: Boolean = false,
    val showAddBottleDialog: Boolean = false,
    val showReplaceBottleDialog: Boolean = false,
    val showSkipStepDialog: Boolean = false,
) {
    val totalCount: Int
        get() = stepCounts.sumOf { stepCount -> stepCount.count }
}
