package com.dispensesure.retail.feature.dispense.domain.model

import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.scanning.domain.model.TxnDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DispenseCountUiStateTest {

    private fun stepCount(countId: Long, count: Int) = TxnDetail(
        txnDetailId = countId, count = count, createdAt = 0L, image = null, type = StepState.TARGET_VERIFICATION,
    )

    @Test
    fun defaults_areCorrect() {
        val state = DispenseCountUiState()
        assertEquals("", state.drugName)
        assertNull(state.bucketId)
        assertEquals(0, state.targetCount)
        assertTrue(state.stepCounts.isEmpty())
        assertEquals(0, state.totalCount)
        assertEquals(StepState.TARGET_VERIFICATION, state.currentStep)
        assertTrue(state.steps.isEmpty())
        assertFalse(state.isTxnLoaded)
        assertFalse(state.canReset)
        assertNull(state.capturedStill)
        assertFalse(state.showFlash)
        assertFalse(state.isCapturing)
        assertFalse(state.showTargetCountDialog)
        assertFalse(state.showNotesDialog)
        assertFalse(state.showCountMismatchDialog)
        assertFalse(state.showAddBottleDialog)
        assertFalse(state.showReplaceBottleDialog)
        assertFalse(state.showSkipStepDialog)
    }

    @Test
    fun totalCount_sumsTheStepCounts() {
        val state = DispenseCountUiState(stepCounts = listOf(stepCount(1L, 3), stepCount(2L, 4)))

        assertEquals(7, state.totalCount)
    }

    @Test
    fun copy_overrides() {
        val state = DispenseCountUiState().copy(currentStep = StepState.VIAL, targetCount = 30)

        assertEquals(StepState.VIAL, state.currentStep)
        assertEquals(30, state.targetCount)
    }
}
