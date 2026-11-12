package com.dispensesure.retail.feature.dispense.presentation.viewmodel

import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.scanning.engine.IdleWatchdog.Companion.IDLE_TIMEOUT_MS
import com.dispensesure.retail.feature.dispense.domain.model.DispenseCountAction
import com.dispensesure.retail.feature.dispense.domain.model.DispenseStage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [DispenseCountViewModel]'s start step with no txn, and that the engine's
 * idle pause reaches the state the screen reads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DispenseCountViewModelFrameTest : DispenseCountViewModelTestBase() {

    // SCAN_VM_017
    @Test
    fun `counting with no txn starts on TARGET_VERIFICATION`() = runTest {
        // No txn, so no drug: a drug with no type starts on the prescribed count.
        viewModel.onAction(DispenseCountAction.StageChanged(DispenseStage.COUNTING, txnId = 0L, isHazardousDrug = false))
        advanceUntilIdle()

        assertEquals(StepState.TARGET_VERIFICATION, viewModel.uiState.value.currentStep)
        assertFalse(viewModel.uiState.value.isTxnLoaded)
    }

    // SCAN_VM_040 (view model side; the watchdog itself is in PillCountingEngineTest)
    @Test
    fun `the engine's idle pause reaches the engine state the screen reads`() = runTest {
        advanceTimeBy(IDLE_TIMEOUT_MS + 1_000)
        runCurrent()

        assertTrue(viewModel.engineState.value.showIdleOverlay)
        assertTrue(viewModel.engineState.value.isCameraPaused)
    }
}
