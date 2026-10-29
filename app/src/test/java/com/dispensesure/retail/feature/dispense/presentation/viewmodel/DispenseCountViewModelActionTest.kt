package com.dispensesure.retail.feature.dispense.presentation.viewmodel

import com.dispensesure.retail.R
import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.room.models.enums.CountStatus
import com.dispensesure.retail.core.scanning.domain.model.DetectedPill
import com.dispensesure.retail.core.scanning.engine.feedGloveFrameForTest
import com.dispensesure.retail.core.scanning.engine.feedTrayFrameForTest
import com.dispensesure.retail.core.scanning.engine.putFrameForTest
import com.dispensesure.retail.core.scanning.logic.TrayColor
import com.dispensesure.retail.feature.dispense.domain.model.DispenseCountAction
import com.dispensesure.retail.feature.dispense.domain.model.DispenseCountEffect
import com.dispensesure.retail.feature.dispense.domain.model.DispenseStage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [DispenseCountViewModel] actions: camera and engine plumbing, View All
 * delete, notes, completion and leaving for the queue.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DispenseCountViewModelActionTest : DispenseCountViewModelTestBase() {

    // ─────────────────────────── engine state and events ───────────────────────────

    @Test
    fun `resetForRecount returns false when the DB reset fails`() = runTest {
        coEvery { preferenceHelper.getTxnId() } returns 7L
        coEvery { dispenseCountRepository.reset(7L) } throws IllegalStateException("boom")

        assertFalse(viewModel.resetForRecount())
    }

    @Test
    fun `resetForRecount returns true when the reset succeeds`() = runTest {
        coEvery { preferenceHelper.getTxnId() } returns 7L

        assertTrue(viewModel.resetForRecount())
    }

    // SCAN_VM_029 (view model side)
    @Test
    fun `drawn pills reach the engine state`() = runTest {
        val newPills = listOf(DetectedPill(x = 10f, y = 20f, confidence = 0.9f))

        viewModel.onAction(DispenseCountAction.FilteredPillsDrawn(newPills))
        runCurrent()

        assertEquals(newPills, viewModel.engineState.value.filteredPills)
    }

    @Test
    fun `addPops re-emits the engine's +N pop`() = runTest {
        val addPopCounts = mutableListOf<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.addPops.toList(addPopCounts) }
        engineFactory.engine.putFrameForTest(mockk(relaxed = true))

        engineFactory.engine.captureCount(5)

        assertEquals(listOf(5), addPopCounts)
    }

    @Test
    fun `gloves confirmed by the engine are saved on the dispense txn`() = runTest {
        every { preferenceHelper.getTxnId() } returns 42L
        runCurrent() // starts the view model's event collector

        repeat(3) { engineFactory.engine.feedGloveFrameForTest(confidence = 0.9f) }
        advanceUntilIdle()

        coVerify { dispenseCountRepository.saveGlovesPresent(42L) }
        assertTrue(viewModel.engineState.value.glovesDetected)
    }

    @Test
    fun `a tray classification is saved on the dispense txn`() = runTest {
        every { preferenceHelper.getTxnId() } returns 42L
        runCurrent()

        viewModel.onAction(DispenseCountAction.TrayClassified(TrayColor.RED, isHazardous = true))
        advanceUntilIdle()

        coVerify { dispenseCountRepository.saveHazardousTrayDetected(42L, true) }
        assertNull(viewModel.engineState.value.pendingTrayColor)
    }

    @Test
    fun `a failed tray save does not crash`() = runTest {
        every { preferenceHelper.getTxnId() } returns 42L
        coEvery {
            dispenseCountRepository.saveHazardousTrayDetected(
                any(),
                any(),
            )
        } throws RuntimeException("db down")
        runCurrent()

        viewModel.onAction(DispenseCountAction.TrayClassified(TrayColor.RED, isHazardous = true))
        advanceUntilIdle()

        coVerify { dispenseCountRepository.saveHazardousTrayDetected(42L, true) }
    }

    @Test
    fun `a tray warning from the engine is shown as a toast`() = runTest {
        every { preferenceHelper.getHazardousTrayColor() } returns "RED"
        val effects = collectEffects()
        runCurrent() // starts the view model's event collector
        viewModel.onAction(DispenseCountAction.StageChanged(DispenseStage.COUNTING, isHazardousDrug = false))
        // runCurrent, not advanceUntilIdle: moving the clock would fire the 60 s idle pause,
        // which turns tray checks off.
        runCurrent()

        engineFactory.engine.feedTrayFrameForTest(TrayColor.RED)
        runCurrent()

        assertEquals(listOf(DispenseCountEffect.ShowToast(R.string.hazardous_tray_warning)), effects)
    }

    // SCAN_VM_038
    @Test
    fun `a flow overlay pauses detection and clears the pills and trays`() = runTest {
        viewModel.onAction(DispenseCountAction.FlowOverlayChanged(isShown = false))
        viewModel.onAction(DispenseCountAction.FlowOverlayChanged(isShown = true))

        assertTrue(viewModel.engineState.value.detectedPills.isEmpty())
        assertTrue(viewModel.engineState.value.trayDetections.isEmpty())
    }

    // ─────────────────────────── leaving for the queue ───────────────────────────

    // SCAN_VM_028
    @Test
    fun `leaving for the queue clears the steps`() = runTest {
        setState { it.copy(steps = listOf(StepState.SCAN, StepState.TARGET_VERIFICATION), currentStep = StepState.SCAN) }

        viewModel.onAction(DispenseCountAction.LeavingForQueue(resetGloves = false))

        assertTrue(viewModel.uiState.value.steps.isEmpty())
        assertEquals(StepState.TARGET_VERIFICATION, viewModel.uiState.value.currentStep)
    }

    @Test
    fun `leaving for the queue from the Rx scan also clears the gloves`() = runTest {
        runCurrent()
        repeat(3) { engineFactory.engine.feedGloveFrameForTest(confidence = 0.9f) }
        advanceUntilIdle()
        assertTrue(viewModel.engineState.value.glovesDetected)

        viewModel.onAction(DispenseCountAction.LeavingForQueue(resetGloves = true))

        assertFalse(viewModel.engineState.value.glovesDetected)
    }

    // ─────────────────────────── View All delete ───────────────────────────

    // SCAN_VM_031
    @Test
    fun `CountDeleted deletes the count through the repository`() = runTest {
        every { preferenceHelper.getTxnId() } returns 9L

        viewModel.onAction(DispenseCountAction.CountDeleted(countId = 5L))
        advanceUntilIdle()

        coVerify(exactly = 1) { dispenseCountRepository.deleteCount(9L, 5L) }
    }

    // ─────────────────────────── notes ───────────────────────────

    // SCAN_VM_033
    @Test
    fun `NoteSaved hides the notes dialog, saves the note and completes`() = runTest {
        every { preferenceHelper.getTxnId() } returns 42L
        setState { it.copy(showNotesDialog = true) }

        viewModel.onAction(DispenseCountAction.NoteSaved(note = "test note"))
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showNotesDialog)
        coVerify(exactly = 1) { dispenseCountRepository.saveNote(42L, "test note") }
        coVerify(exactly = 1) { dispenseCountRepository.complete(42L) }
    }

    // SCAN_VM_034
    @Test
    fun `NoteSkipped hides the notes dialog and completes without a note`() = runTest {
        setState { it.copy(showNotesDialog = true) }

        viewModel.onAction(DispenseCountAction.NoteSkipped)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showNotesDialog)
        coVerify(exactly = 0) { dispenseCountRepository.saveNote(any(), any()) }
        coVerify(exactly = 1) { dispenseCountRepository.complete(any()) }
    }

    // SCAN_VM_008 (replaced)
    @Test
    fun `NoteDismissed hides the notes dialog without completing`() = runTest {
        setState { it.copy(showNotesDialog = true) }

        viewModel.onAction(DispenseCountAction.NoteDismissed)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showNotesDialog)
        coVerify(exactly = 0) { dispenseCountRepository.complete(any()) }
    }

    // ─────────────────────────── Done on the pour-out ───────────────────────────

    // SCAN_VM_036
    @Test
    fun `Done below the pour-out target shows the target toast`() = runTest {
        startCounting(dispenseTxn(targetCount = 10))
        setState { it.copy(currentStep = StepState.CONTAINER_INITIATE) }
        val effects = collectEffects()

        viewModel.onAction(DispenseCountAction.DoneTapped)

        assertEquals(
            listOf(DispenseCountEffect.ShowToast(R.string.pills_count_should_be_greater_than_target_count)),
            effects
        )
        assertEquals(StepState.CONTAINER_INITIATE, viewModel.uiState.value.currentStep)
    }

    // ─────────────────────────── completion ───────────────────────────

    @Test
    fun `a completed dispense clears the still and tells the screen it is finished`() = runTest {
        coEvery { dispenseCountRepository.complete(any()) } returns CountStatus.COMPLETED
        setState { it.copy(capturedStill = mockk(relaxed = true)) }
        val effects = collectEffects()

        viewModel.onAction(DispenseCountAction.NoteSkipped)
        advanceUntilIdle()

        assertEquals(listOf<DispenseCountEffect>(DispenseCountEffect.Finished), effects)
        assertNull(viewModel.uiState.value.capturedStill)
    }

    @Test
    fun `nothing is finished when there was nothing to complete`() = runTest {
        coEvery { dispenseCountRepository.complete(any()) } returns null
        val effects = collectEffects()

        viewModel.onAction(DispenseCountAction.NoteSkipped)
        advanceUntilIdle()

        assertTrue(effects.isEmpty())
    }

    @Test
    fun `a dispense that found nothing to complete can complete again`() = runTest {
        coEvery { dispenseCountRepository.complete(any()) } returnsMany listOf(
            null,
            CountStatus.COMPLETED,
        )
        val effects = collectEffects()

        viewModel.onAction(DispenseCountAction.NoteSkipped)
        advanceUntilIdle()
        viewModel.onAction(DispenseCountAction.NoteSkipped)
        advanceUntilIdle()

        assertEquals(listOf<DispenseCountEffect>(DispenseCountEffect.Finished), effects)
    }

    @Test
    fun `a dispense that failed to complete can complete again`() = runTest {
        coEvery { dispenseCountRepository.complete(any()) } throws RuntimeException("db down") andThen CountStatus.COMPLETED
        val effects = collectEffects()

        viewModel.onAction(DispenseCountAction.NoteSkipped)
        advanceUntilIdle()
        viewModel.onAction(DispenseCountAction.NoteSkipped)
        advanceUntilIdle()

        assertEquals(
            listOf(
                DispenseCountEffect.ShowToast(R.string.error_generic),
                DispenseCountEffect.Finished,
            ),
            effects,
        )
    }

    @Test
    fun `the next count completes after the last one did`() = runTest {
        coEvery { dispenseCountRepository.complete(any()) } returns CountStatus.COMPLETED
        viewModel.onAction(DispenseCountAction.NoteSkipped)
        advanceUntilIdle()

        startCounting(dispenseTxn(txnId = 43L))
        viewModel.onAction(DispenseCountAction.NoteSkipped)
        advanceUntilIdle()

        coVerify { dispenseCountRepository.complete(43L) }
    }
}
