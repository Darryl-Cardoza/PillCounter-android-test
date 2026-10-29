package com.dispensesure.retail.core.scanning.presentation.compose.count

import com.dispensesure.retail.R
import com.dispensesure.retail.core.models.StepState
import java.util.ArrayDeque

/** Everything the count panel draws. Each counting flow builds one from its own state. */
data class CountPanelState(
    val drugName: String,
    val ndc: String,
    val strength: String,
    val bucket: String,
    val dosageForm: String,
    val drugImage: String?,
    // Pills already added on this step, and the order's target (0 = none yet).
    val totalCount: Int,
    val targetCount: Int,
    // A stock loose count: no target, so no progress bar and no "All Done".
    val isOpenCount: Boolean,
    val stepType: StepState,
    val steps: List<StepState>,
    val isVoiceOverEnabled: Boolean,
    // Pills on the tray now, and the engine's recent per-frame counts behind the circle's arc.
    val detectedCount: Int,
    val recentDetectionCounts: ArrayDeque<Int>,
    val isAddCooldown: Boolean,
    val isIdle: Boolean,
    val showGloveIcon: Boolean,
    val glovesDetected: Boolean,
    // VIAL step: a still is on screen / a capture is in flight.
    val hasStill: Boolean,
    val isCapturing: Boolean,
) {
    val isFixed: Boolean
        get() = !isOpenCount && stepType != StepState.CONTAINER_INITIATE

    val isAllDone: Boolean
        get() = isFixed && targetCount > 0 && totalCount >= targetCount

    // Stock count (open count) has no target count, so the centre circle never
    // reaches "All Done" — surface a Proceed button to finish the count instead.
    val showProceed: Boolean
        get() = stepType == StepState.CONTAINER_INITIATE ||
            stepType == StepState.CONTAINER_PENDING ||
            isOpenCount

    // REGULAR labels the counting step "Scan Open Pills".
    val stepTitleOverrides: Map<StepState, Int>
        get() = if (isOpenCount) {
            mapOf(StepState.TARGET_VERIFICATION to R.string.scan_open_pills)
        } else {
            emptyMap()
        }
}
