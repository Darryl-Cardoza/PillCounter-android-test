package com.dispensesure.retail.feature.dispense.domain

import com.dispensesure.retail.feature.dispense.domain.model.DispenseStage
import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for [DispenseBackPress]. */
class DispenseBackPressTest {

    private fun onDeviceBack(
        isHistoryShown: Boolean = false,
        isIdleOverlayShown: Boolean = false,
        stage: DispenseStage = DispenseStage.PRE_RX,
        isFromQueue: Boolean = false,
    ) = DispenseBackPress.onDeviceBack(isHistoryShown, isIdleOverlayShown, stage, isFromQueue)

    // ─────────────────────────── device back ───────────────────────────

    @Test
    fun `an open history view closes first`() {
        assertEquals(
            BackPressOutcome.CloseHistory,
            onDeviceBack(isHistoryShown = true, isIdleOverlayShown = true, stage = DispenseStage.QUEUE, isFromQueue = true),
        )
    }

    @Test
    fun `the idle overlay resumes before anything leaves`() {
        assertEquals(
            BackPressOutcome.ResumeFromIdle,
            onDeviceBack(isIdleOverlayShown = true, stage = DispenseStage.QUEUE, isFromQueue = true),
        )
    }

    @Test
    fun `the queue goes back to the Dashboard`() {
        assertEquals(BackPressOutcome.LeaveToDashboard, onDeviceBack(stage = DispenseStage.QUEUE))
        assertEquals(BackPressOutcome.LeaveToDashboard, onDeviceBack(stage = DispenseStage.QUEUE, isFromQueue = true))
    }

    @Test
    fun `an Rx scan opened from the queue goes back to the queue`() {
        assertEquals(BackPressOutcome.ReturnToQueue, onDeviceBack(stage = DispenseStage.PRE_RX, isFromQueue = true))
    }

    @Test
    fun `later stages opened from the queue pop back`() {
        assertEquals(BackPressOutcome.PopBack, onDeviceBack(stage = DispenseStage.PRE_NDC, isFromQueue = true))
        assertEquals(BackPressOutcome.PopBack, onDeviceBack(stage = DispenseStage.COUNTING, isFromQueue = true))
    }

    @Test
    fun `an Rx scan not opened from the queue pops back`() {
        assertEquals(BackPressOutcome.PopBack, onDeviceBack(stage = DispenseStage.PRE_RX, isFromQueue = false))
    }

    // ─────────────────────────── back arrow ───────────────────────────

    @Test
    fun `the arrow on an Rx scan opened from the queue goes back to the queue`() {
        assertEquals(BackPressOutcome.ReturnToQueue, DispenseBackPress.onBackArrow(DispenseStage.PRE_RX, isFromQueue = true))
    }

    @Test
    fun `the arrow anywhere else goes to the Dashboard`() {
        assertEquals(BackPressOutcome.LeaveToDashboard, DispenseBackPress.onBackArrow(DispenseStage.PRE_RX, isFromQueue = false))
        assertEquals(BackPressOutcome.LeaveToDashboard, DispenseBackPress.onBackArrow(DispenseStage.PRE_NDC, isFromQueue = true))
        assertEquals(BackPressOutcome.LeaveToDashboard, DispenseBackPress.onBackArrow(DispenseStage.QUEUE, isFromQueue = true))
        assertEquals(BackPressOutcome.LeaveToDashboard, DispenseBackPress.onBackArrow(DispenseStage.COUNTING, isFromQueue = false))
    }
}
