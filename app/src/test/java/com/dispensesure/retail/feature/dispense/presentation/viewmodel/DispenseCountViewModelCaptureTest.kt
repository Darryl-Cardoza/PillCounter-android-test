package com.dispensesure.retail.feature.dispense.presentation.viewmodel

import android.graphics.Bitmap
import com.dispensesure.retail.R
import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.scanning.logic.CameraHelper
import com.dispensesure.retail.core.tourguide.FtueController
import com.dispensesure.retail.core.tourguide.TourSection
import com.dispensesure.retail.core.utils.common.SoundUtils
import com.dispensesure.retail.feature.dispense.domain.model.DispenseCountAction
import com.dispensesure.retail.feature.dispense.domain.model.DispenseCountEffect
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for the single-tap contract on the VIAL photo capture.
 *
 * A capture's bitmap arrives asynchronously, so these tests hold the
 * CameraHelper callback in a MockK slot and invoke it by hand — that is what
 * lets a test sit inside the "capture in flight" window and tap again, which is
 * exactly the window the multi-tap bug lived in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DispenseCountViewModelCaptureTest : DispenseCountViewModelTestBase() {

    private val cameraHelper: CameraHelper = mockk(relaxed = true)

    @Before
    fun setUpCamera() {
        // playCaptureSound drives MediaActionSound, a native API that unit tests
        // cannot load, so stub the object out.
        mockkObject(SoundUtils)
        every { SoundUtils.playCaptureSound() } returns Unit

        viewModel.onAction(DispenseCountAction.CameraHelperReady(cameraHelper))
        setState { it.copy(currentStep = StepState.VIAL) }
    }

    /** Stub the CONTAINER_INITIATE total the back-count gate reads; other steps stay at 0. */
    private fun seedContainerInitiateTotal(total: Int) {
        coEvery { dispenseCountRepository.stepPillTotal(any(), StepState.CONTAINER_INITIATE) } returns total
    }

    // ─────────────────────────── capture button ───────────────────────────

    @Test
    fun `captureVialForTour returns without timing out when the notes setting is off`() = runTest {
        mockkStatic(Bitmap::class)
        every { Bitmap.createBitmap(any<Int>(), any<Int>(), any<Bitmap.Config>()) } returns mockk(relaxed = true)
        every { preferenceHelper.getTxnId() } returns 42L
        every { preferenceHelper.getShowNotesDialogSetting() } returns false
        coEvery { dispenseCountRepository.totalPillCount(any()) } returns 30
        // captureVialForTour is a no-op without an active tour, and refuses a non-demo txn.
        coEvery { dispenseCountRepository.isDemoTxn(42L) } returns true
        FtueController.start(listOf(TourSection.DISPENSE), hl7Enabled = false, notesEnabled = false)

        try {
            // Throws TimeoutCancellationException (virtual 8s) if the flow never leaves VIAL.
            viewModel.captureVialForTour()
        } finally {
            FtueController.end() // process-wide singleton: don't leak the tour into other tests
        }
    }

    @Test
    fun `a capture tap is ignored while the first capture is still in flight`() = runTest {
        every { cameraHelper.captureImage(any(), any()) } returns true

        viewModel.onAction(DispenseCountAction.VialCaptureTapped)
        viewModel.onAction(DispenseCountAction.VialCaptureTapped)
        viewModel.onAction(DispenseCountAction.VialCaptureTapped)

        // Only the first tap reaches the camera; the rest are inert.
        verify(exactly = 1) { cameraHelper.captureImage(any(), any()) }
        verify(exactly = 1) { SoundUtils.playCaptureSound() }
        assertTrue(viewModel.uiState.value.isCapturing)
    }

    @Test
    fun `a new capture tap is accepted once the previous capture delivers its bitmap`() = runTest {
        val onCaptured = slot<(Bitmap) -> Unit>()
        every { cameraHelper.captureImage(capture(onCaptured), any()) } returns true
        val bitmap: Bitmap = mockk(relaxed = true)

        viewModel.onAction(DispenseCountAction.VialCaptureTapped)
        onCaptured.captured.invoke(bitmap)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isCapturing)
        assertSame(bitmap, viewModel.uiState.value.capturedStill)

        viewModel.onAction(DispenseCountAction.VialCaptureTapped)
        verify(exactly = 2) { cameraHelper.captureImage(any(), any()) }
    }

    @Test
    fun `a capture error clears isCapturing`() = runTest {
        val onCaptureError = slot<(Throwable) -> Unit>()
        every { cameraHelper.captureImage(any(), capture(onCaptureError)) } returns true

        viewModel.onAction(DispenseCountAction.VialCaptureTapped)
        onCaptureError.captured.invoke(RuntimeException("capture failed"))
        advanceUntilIdle()

        // A failed capture must not leave the shutter permanently disabled.
        assertFalse(viewModel.uiState.value.isCapturing)
        assertNull(viewModel.uiState.value.capturedStill)

        viewModel.onAction(DispenseCountAction.VialCaptureTapped)
        verify(exactly = 2) { cameraHelper.captureImage(any(), any()) }
    }

    @Test
    fun `a refused capture request clears isCapturing`() = runTest {
        every { cameraHelper.captureImage(any(), any()) } returns false

        viewModel.onAction(DispenseCountAction.VialCaptureTapped)

        assertFalse(viewModel.uiState.value.isCapturing)
    }

    @Test
    fun `re-attaching the camera helper clears a stuck isCapturing flag`() = runTest {
        every { cameraHelper.captureImage(any(), any()) } returns true

        // Camera unbound mid-capture: neither callback ever arrives.
        viewModel.onAction(DispenseCountAction.VialCaptureTapped)
        assertTrue(viewModel.uiState.value.isCapturing)

        viewModel.onAction(DispenseCountAction.CameraHelperReady(cameraHelper))

        assertFalse(viewModel.uiState.value.isCapturing)
        viewModel.onAction(DispenseCountAction.VialCaptureTapped)
        verify(exactly = 2) { cameraHelper.captureImage(any(), any()) }
    }

    @Test
    fun `a bitmap arriving after the VIAL step ended is dropped`() = runTest {
        val onCaptured = slot<(Bitmap) -> Unit>()
        every { cameraHelper.captureImage(capture(onCaptured), any()) } returns true
        val bitmap: Bitmap = mockk(relaxed = true)

        viewModel.onAction(DispenseCountAction.VialCaptureTapped)
        // The user finished the vial step before this capture came back.
        setState { it.copy(currentStep = StepState.CONTAINER_PENDING) }
        onCaptured.captured.invoke(bitmap)
        advanceUntilIdle()

        // Repopulating the still here would re-show the still overlay on
        // top of the next step with no way back to the live camera.
        assertNull(viewModel.uiState.value.capturedStill)
        assertFalse(viewModel.uiState.value.isCapturing)
    }

    @Test
    fun `rapid matched vial labels write the vial photo exactly once`() = runTest {
        val onCaptured = slot<(Bitmap) -> Unit>()
        every { cameraHelper.captureImage(capture(onCaptured), any()) } returns true
        every { preferenceHelper.getTxnId() } returns 42L
        setState { it.copy(steps = listOf(StepState.TARGET_VERIFICATION, StepState.VIAL)) }
        val bitmap: Bitmap = mockk(relaxed = true)

        viewModel.onAction(DispenseCountAction.VialLabelMatched)
        viewModel.onAction(DispenseCountAction.VialLabelMatched)
        viewModel.onAction(DispenseCountAction.VialLabelMatched)
        onCaptured.captured.invoke(bitmap)
        advanceUntilIdle()

        // Three matches, one commit.
        coVerify(exactly = 1) { dispenseCountRepository.saveVialPhoto(42L, bitmap, any()) }
    }

    @Test
    fun `a matched vial label commits on its own when VIAL is the last step`() = runTest {
        val onCaptured = slot<(Bitmap) -> Unit>()
        every { cameraHelper.captureImage(capture(onCaptured), any()) } returns true
        setState { it.copy(steps = listOf(StepState.TARGET_VERIFICATION, StepState.VIAL)) }

        viewModel.onAction(DispenseCountAction.VialLabelMatched)
        onCaptured.captured.invoke(mockk(relaxed = true))
        advanceUntilIdle()

        coVerify(exactly = 1) { dispenseCountRepository.saveVialPhoto(any(), any(), any()) }
    }

    @Test
    fun `a matched vial label only captures when the back count follows`() = runTest {
        val onCaptured = slot<(Bitmap) -> Unit>()
        every { cameraHelper.captureImage(capture(onCaptured), any()) } returns true
        setState { it.copy(steps = listOf(StepState.VIAL, StepState.CONTAINER_PENDING)) }
        val bitmap: Bitmap = mockk(relaxed = true)

        viewModel.onAction(DispenseCountAction.VialLabelMatched)
        onCaptured.captured.invoke(bitmap)
        advanceUntilIdle()

        assertSame(bitmap, viewModel.uiState.value.capturedStill)
        coVerify(exactly = 0) { dispenseCountRepository.saveVialPhoto(any(), any(), any()) }
    }

    // ─────────────────────────── done button ───────────────────────────

    /** Capture → bitmap delivered. Returns the still. */
    private fun captureStill(): Bitmap {
        val onCaptured = slot<(Bitmap) -> Unit>()
        every { cameraHelper.captureImage(capture(onCaptured), any()) } returns true
        val bitmap: Bitmap = mockk(relaxed = true)
        viewModel.onAction(DispenseCountAction.VialCaptureTapped)
        onCaptured.captured.invoke(bitmap)
        return bitmap
    }

    @Test
    fun `the vial photo is saved once even when Done is tapped twice`() = runTest {
        every { preferenceHelper.getTxnId() } returns 42L

        captureStill()
        viewModel.onAction(DispenseCountAction.VialSaveTapped)
        viewModel.onAction(DispenseCountAction.VialSaveTapped)
        advanceUntilIdle()

        coVerify(exactly = 1) { dispenseCountRepository.saveVialPhoto(any(), any(), any()) }
    }

    @Test
    fun `Done keeps working after the notes dialog is dismissed`() = runTest {
        every { preferenceHelper.getTxnId() } returns 42L
        every { preferenceHelper.getShowNotesDialogSetting() } returns true
        coEvery { dispenseCountRepository.totalPillCount(any()) } returns 30

        captureStill()
        viewModel.onAction(DispenseCountAction.VialSaveTapped)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.showNotesDialog)

        // Back button / outside tap closes the notes dialog and leaves the user on
        // VIAL with the still — Done must re-arm the flow instead of going dead.
        viewModel.onAction(DispenseCountAction.NoteDismissed)
        viewModel.onAction(DispenseCountAction.VialSaveTapped)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showNotesDialog)
        coVerify(exactly = 1) { dispenseCountRepository.saveVialPhoto(any(), any(), any()) }
    }

    @Test
    fun `a fresh still re-arms Done without a redo`() = runTest {
        captureStill()
        viewModel.onAction(DispenseCountAction.VialSaveTapped)
        advanceUntilIdle()

        // No redo in between: the new still is unsaved, so Done must commit it.
        captureStill()
        viewModel.onAction(DispenseCountAction.VialSaveTapped)
        advanceUntilIdle()

        coVerify(exactly = 2) { dispenseCountRepository.saveVialPhoto(any(), any(), any()) }
    }

    @Test
    fun `a redo re-arms Done for the next still`() = runTest {
        captureStill()
        viewModel.onAction(DispenseCountAction.VialSaveTapped)
        advanceUntilIdle()

        // Redo → recapture → Done must still commit.
        viewModel.onAction(DispenseCountAction.VialRedoTapped)
        setState { it.copy(currentStep = StepState.VIAL) }
        captureStill()
        viewModel.onAction(DispenseCountAction.VialSaveTapped)
        advanceUntilIdle()

        coVerify(exactly = 2) { dispenseCountRepository.saveVialPhoto(any(), any(), any()) }
    }

    // ─────────────────────────── back-count skip ───────────────────────────

    /** Counting a txn with a target of 30, on VIAL with [steps]. */
    private fun TestScope.onVialOf(steps: List<StepState>) {
        startCounting(dispenseTxn(targetCount = 30))
        setState { it.copy(steps = steps, currentStep = StepState.VIAL) }
    }

    @Test
    fun `vial Done offers the skip dialog when the stock bottle was emptied exactly`() = runTest {
        onVialOf(listOf(StepState.VIAL, StepState.CONTAINER_PENDING))
        seedContainerInitiateTotal(30)

        captureStill()
        viewModel.onAction(DispenseCountAction.VialSaveTapped)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showSkipStepDialog)
        assertEquals(StepState.VIAL, viewModel.uiState.value.currentStep)
    }

    @Test
    fun `vial Done advances to the back count when pills are left in the bottle`() = runTest {
        onVialOf(listOf(StepState.VIAL, StepState.CONTAINER_PENDING))
        seedContainerInitiateTotal(50)

        captureStill()
        viewModel.onAction(DispenseCountAction.VialSaveTapped)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showSkipStepDialog)
        assertEquals(StepState.CONTAINER_PENDING, viewModel.uiState.value.currentStep)
    }

    @Test
    fun `vial Done never offers the skip dialog when VIAL is the last step`() = runTest {
        onVialOf(listOf(StepState.TARGET_VERIFICATION, StepState.VIAL))
        seedContainerInitiateTotal(30)
        coEvery { dispenseCountRepository.totalPillCount(any()) } returns 0
        val effects = collectEffects()

        captureStill()
        viewModel.onAction(DispenseCountAction.VialSaveTapped)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showSkipStepDialog)
        assertEquals(listOf(DispenseCountEffect.ShowToast(R.string.no_transaction_found)), effects)
    }

    @Test
    fun `Skip clears the dialog and runs the done path`() = runTest {
        onVialOf(listOf(StepState.VIAL, StepState.CONTAINER_PENDING))
        every { preferenceHelper.getShowNotesDialogSetting() } returns true
        coEvery { dispenseCountRepository.totalPillCount(any()) } returns 30
        // Raise the skip dialog so a "clears it" assertion is not passing on the default.
        setState { it.copy(showSkipStepDialog = true) }

        viewModel.onAction(DispenseCountAction.SkipBackCountTapped)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showSkipStepDialog)
        assertEquals(StepState.VIAL, viewModel.uiState.value.currentStep)
        assertTrue(viewModel.uiState.value.showNotesDialog)
    }
}
