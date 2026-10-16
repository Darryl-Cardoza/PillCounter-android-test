package com.dispensesure.retail.feature.dispense.domain

import com.dispensesure.retail.feature.dispense.domain.model.DispenseStage

/** What a back press on the dispense screen does. */
enum class BackPressOutcome { CloseHistory, ResumeFromIdle, ReturnToQueue, LeaveToDashboard, PopBack }

/** The dispense screen's back rules. Values in, an outcome out. */
object DispenseBackPress {

    /**
     * Device-back priority order:
     *  1. If the history view is open, dismiss it (return to camera + pill panel).
     *  2. If the idle overlay is up, just resume — back on a paused screen must
     *     not leave it.
     *  3. In QUEUE stage: back navigates to Dashboard (queue is the home for dispense).
     *  4. In non-QUEUE stage with fromQueue=true: cancel current scan, return to QUEUE.
     *  5. Otherwise: exit the dispense flow to the Dashboard.
     */
    fun onDeviceBack(
        isHistoryShown: Boolean,
        isIdleOverlayShown: Boolean,
        stage: DispenseStage,
        isFromQueue: Boolean,
    ): BackPressOutcome = when {
        isHistoryShown -> BackPressOutcome.CloseHistory
        isIdleOverlayShown -> BackPressOutcome.ResumeFromIdle
        stage == DispenseStage.QUEUE -> BackPressOutcome.LeaveToDashboard
        // Cancel RX scan before any transaction is created → return to queue list.
        isFromQueue && stage == DispenseStage.PRE_RX -> BackPressOutcome.ReturnToQueue
        else -> BackPressOutcome.PopBack
    }

    /** The on-screen back arrow: back to the queue from an Rx scan opened there, else the Dashboard. */
    fun onBackArrow(stage: DispenseStage, isFromQueue: Boolean): BackPressOutcome =
        if (isFromQueue && stage == DispenseStage.PRE_RX) {
            BackPressOutcome.ReturnToQueue
        } else {
            BackPressOutcome.LeaveToDashboard
        }
}
