package com.dispensesure.retail.feature.dispense.presentation.viewmodel

import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.scanning.domain.model.TxnDetail
import com.dispensesure.retail.feature.dispense.domain.model.DispenseCountAction
import io.mockk.coVerify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [DispenseCountViewModel]'s simple state changes: dialogs, target, camera pause, gloves. */
@OptIn(ExperimentalCoroutinesApi::class)
class DispenseCountViewModelStateTest : DispenseCountViewModelTestBase() {

    // ─────────────────────────── loaded details and target ───────────────────────────

    @Test
    fun `starting to count loads the drug details`() = runTest {
        startCounting(dispenseTxn(ndc = "NDC1", targetCount = 30, bucketId = " "))

        with(viewModel.uiState.value) {
            assertEquals("Drug", drugName)
            assertEquals("NDC1", ndc)
            assertEquals(30, targetCount)
            // A blank bucket shows the default one.
            assertNull(bucketId)
            assertTrue(isTxnLoaded)
            assertFalse(showTargetCountDialog)
        }
    }

    // SCAN_VM_006 (replaced)
    @Test
    fun `starting to count with no order target asks for one`() = runTest {
        startCounting(dispenseTxn(targetCount = null))

        assertTrue(viewModel.uiState.value.showTargetCountDialog)
    }

    // SCAN_VM_010
    @Test
    fun `TargetCountEntered stores the target, saves it and closes the dialog`() = runTest {
        selectTxn(42L)
        setState { it.copy(showTargetCountDialog = true) }

        viewModel.onAction(DispenseCountAction.TargetCountEntered(42))
        advanceUntilIdle()

        assertEquals(42, viewModel.uiState.value.targetCount)
        assertFalse(viewModel.uiState.value.showTargetCountDialog)
        coVerify { dispenseCountRepository.saveTargetCount(42L, 42) }
    }

    @Test
    fun `a typed-in target lets Done leave the pour-out`() = runTest {
        startCounting(dispenseTxn(targetCount = null))
        viewModel.onAction(DispenseCountAction.TargetCountEntered(30))
        setState {
            it.copy(
                steps = listOf(
                    StepState.SCAN,
                    StepState.CONTAINER_INITIATE,
                    StepState.TARGET_VERIFICATION,
                    StepState.VIAL,
                ),
                currentStep = StepState.CONTAINER_INITIATE,
                stepCounts = listOf(
                    TxnDetail(
                        txnDetailId = 1L,
                        count = 30,
                        createdAt = 0L,
                        image = null,
                        type = StepState.CONTAINER_INITIATE,
                    ),
                ),
            )
        }

        viewModel.onAction(DispenseCountAction.DoneTapped)

        assertEquals(StepState.TARGET_VERIFICATION, viewModel.uiState.value.currentStep)
    }

    @Test
    fun `starting to count loads the txn once`() = runTest {
        startCounting(dispenseTxn())

        coVerify(exactly = 1) { dispenseCountRepository.loadTxn(42L) }
    }

    // SCAN_VM_007
    @Test
    fun `TargetCountDismissed closes the dialog`() = runTest {
        setState { it.copy(showTargetCountDialog = true) }

        viewModel.onAction(DispenseCountAction.TargetCountDismissed)

        assertFalse(viewModel.uiState.value.showTargetCountDialog)
    }

    // SCAN_VM_003
    @Test
    fun `CountMismatchDismissed closes the mismatch dialog`() = runTest {
        setState { it.copy(showCountMismatchDialog = true) }

        viewModel.onAction(DispenseCountAction.CountMismatchDismissed)

        assertFalse(viewModel.uiState.value.showCountMismatchDialog)
    }

    // ─────────────────────────── camera pause ───────────────────────────

    // SCAN_VM_011
    @Test
    fun `View All pauses the camera and clears the pills`() = runTest {
        viewModel.onAction(DispenseCountAction.HistoryVisibilityChanged(isShown = true))
        runCurrent()

        assertTrue(viewModel.engineState.value.isCameraPaused)
        assertTrue(viewModel.engineState.value.detectedPills.isEmpty())
    }

    // SCAN_VM_012
    @Test
    fun `closing View All resumes the camera`() = runTest {
        viewModel.onAction(DispenseCountAction.HistoryVisibilityChanged(isShown = true))
        runCurrent()
        assertTrue(viewModel.engineState.value.isCameraPaused)

        viewModel.onAction(DispenseCountAction.HistoryVisibilityChanged(isShown = false))
        runCurrent()

        assertFalse(viewModel.engineState.value.isCameraPaused)
    }

    // ─────────────────────────── gloves ───────────────────────────

    // SCAN_VM_013
    @Test
    fun `a new count starts with gloves unconfirmed`() = runTest {
        assertFalse(viewModel.engineState.value.glovesDetected)
    }
}
