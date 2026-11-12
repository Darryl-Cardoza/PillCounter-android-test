package com.dispensesure.retail.feature.dispense.presentation.viewmodel

import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import com.dispensesure.retail.R
import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.room.models.DrugMasterEntity
import com.dispensesure.retail.core.scanning.domain.model.DecodedScan
import com.dispensesure.retail.core.scanning.domain.model.DispenseBottle
import com.dispensesure.retail.core.scanning.engine.frameForTest
import com.dispensesure.retail.core.scanning.engine.lastAddSignatureForTest
import com.dispensesure.retail.core.scanning.engine.putFrameForTest
import com.dispensesure.retail.core.utils.common.HelperFunctions
import com.dispensesure.retail.core.utils.common.OverlayUtils
import com.dispensesure.retail.core.utils.common.PhotoInfo
import com.dispensesure.retail.feature.dispense.domain.model.DispenseCountAction
import com.dispensesure.retail.feature.dispense.domain.model.DispenseCountEffect
import com.dispensesure.retail.feature.dispense.domain.model.DispenseStage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private const val BOTTLE_GTIN = "00300930058123"

/**
 * Unit tests for [DispenseCountViewModel]: stage changes, reset, Done and step moves,
 * bottle rescans and ADD. The rules are in DispenseWorkflowTest and BottleRescanDeciderTest,
 * the data work in DispenseCountRepositoryTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DispenseCountViewModelTest : DispenseCountViewModelTestBase() {

    // ─────────────────────────── StageChanged ───────────────────────────

    @Test
    fun `reaching the container scan publishes the dispense workflow steps`() = runTest {
        coEvery { dispenseCountRepository.loadTxn(7L) } returns dispenseTxn(txnId = 7L)
        coEvery { dispenseCountRepository.loadDrug(1L) } returns DrugMasterEntity(
            drugId = 1L, ndc = "NDC1", drugName = "Drug", drugType = "REGULAR"
        )

        viewModel.onAction(DispenseCountAction.StageChanged(DispenseStage.PRE_NDC, txnId = 7L, isHazardousDrug = false))
        advanceUntilIdle()

        assertEquals(
            listOf(StepState.SCAN, StepState.CONTAINER_INITIATE, StepState.TARGET_VERIFICATION, StepState.VIAL),
            viewModel.uiState.value.steps
        )
    }

    @Test
    fun `reaching the container scan makes SCAN the current step without waiting on the steps lookup`() = runTest {
        viewModel.onAction(DispenseCountAction.StageChanged(DispenseStage.PRE_NDC, txnId = 0L, isHazardousDrug = false))

        assertEquals(StepState.SCAN, viewModel.uiState.value.currentStep)
    }

    @Test
    fun `returning to the queue clears the steps`() = runTest {
        setState { it.copy(steps = listOf(StepState.SCAN, StepState.TARGET_VERIFICATION), currentStep = StepState.SCAN) }

        viewModel.onAction(DispenseCountAction.StageChanged(DispenseStage.QUEUE, txnId = 0L, isHazardousDrug = false))

        assertTrue(viewModel.uiState.value.steps.isEmpty())
        assertEquals(StepState.TARGET_VERIFICATION, viewModel.uiState.value.currentStep)
    }

    @Test
    fun `counting resumes on the saved step and reloads its vial photo`() = runTest {
        startCounting(dispenseTxn(workflowStep = StepState.VIAL.name))

        assertEquals(StepState.VIAL, viewModel.uiState.value.currentStep)
        coVerify { dispenseCountRepository.loadVialPhoto(42L) }
    }

    // ─────────────────────────── resetForRecount ───────────────────────────

    @Test
    fun `resetForRecount resets the txn and lands on SCAN`() = runTest {
        selectTxn(7L)

        viewModel.resetForRecount()

        coVerify(exactly = 1) { dispenseCountRepository.reset(7L) }
        assertEquals(StepState.SCAN, viewModel.uiState.value.currentStep)
        assertFalse(viewModel.uiState.value.isTxnLoaded)
        assertNull(viewModel.uiState.value.capturedStill)
    }

    @Test
    fun `resetForRecount with no txn deletes nothing`() = runTest {
        viewModel.resetForRecount()

        coVerify(exactly = 0) { dispenseCountRepository.reset(any()) }
    }

    // ─────────────────────────── reset availability ───────────────────────────

    @Test
    fun `reset availability follows the txn while counting`() = runTest {
        val canReset = MutableStateFlow(true)
        every { dispenseCountRepository.observeCanReset(42L) } returns canReset

        startCounting()
        assertTrue(viewModel.uiState.value.canReset)

        canReset.value = false
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.canReset)
    }

    // ─────────────────────────── Done and step moves ───────────────────────────

    @Test
    fun `Done on a met target moves to the next step and saves it`() = runTest {
        startCounting(dispenseTxn(targetCount = 0))
        setState { it.copy(steps = listOf(StepState.TARGET_VERIFICATION, StepState.VIAL), currentStep = StepState.TARGET_VERIFICATION) }

        viewModel.onAction(DispenseCountAction.DoneTapped)
        advanceUntilIdle()

        assertEquals(StepState.VIAL, viewModel.uiState.value.currentStep)
        coVerify { dispenseCountRepository.saveStep(42L, StepState.VIAL) }
    }

    @Test
    fun `Done on the last step with nothing counted shows the no-transaction toast`() = runTest {
        startCounting(dispenseTxn(targetCount = 0))
        setState { it.copy(steps = listOf(StepState.TARGET_VERIFICATION), currentStep = StepState.TARGET_VERIFICATION) }
        coEvery { dispenseCountRepository.totalPillCount(any()) } returns 0
        val effects = collectEffects()

        viewModel.onAction(DispenseCountAction.DoneTapped)
        advanceUntilIdle()

        assertEquals(listOf(DispenseCountEffect.ShowToast(R.string.no_transaction_found)), effects)
    }

    @Test
    fun `Done with an empty step list goes straight to finishing`() = runTest {
        startCounting(dispenseTxn(targetCount = 0))
        setState { it.copy(steps = emptyList(), currentStep = StepState.TARGET_VERIFICATION) }
        coEvery { dispenseCountRepository.totalPillCount(any()) } returns 0
        val effects = collectEffects()

        viewModel.onAction(DispenseCountAction.DoneTapped)
        advanceUntilIdle()

        assertEquals(listOf(DispenseCountEffect.ShowToast(R.string.no_transaction_found)), effects)
    }

    @Test
    fun `Done on the last step shows the notes dialog when the notes setting is on`() = runTest {
        // HL7 txns used to skip the notes prompt; the setting is now the only gate.
        startCounting(dispenseTxn(targetCount = 0, isComingFromHL7 = true))
        setState { it.copy(steps = listOf(StepState.TARGET_VERIFICATION), currentStep = StepState.TARGET_VERIFICATION) }
        every { preferenceHelper.getShowNotesDialogSetting() } returns true
        coEvery { dispenseCountRepository.totalPillCount(any()) } returns 30

        viewModel.onAction(DispenseCountAction.DoneTapped)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showNotesDialog)
        coVerify(exactly = 0) { dispenseCountRepository.complete(any()) }
    }

    @Test
    fun `Done on the last step completes straight through when the notes setting is off`() = runTest {
        startCounting(dispenseTxn(targetCount = 0, isComingFromHL7 = true))
        setState { it.copy(steps = listOf(StepState.TARGET_VERIFICATION), currentStep = StepState.TARGET_VERIFICATION) }
        every { preferenceHelper.getShowNotesDialogSetting() } returns false
        coEvery { dispenseCountRepository.totalPillCount(any()) } returns 30

        viewModel.onAction(DispenseCountAction.DoneTapped)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showNotesDialog)
        // No confirm dialog any more: Done completes the txn straight through.
        coVerify(exactly = 1) { dispenseCountRepository.complete(42L) }
    }

    @Test
    fun `a short back count asks before finishing, and Yes goes on to finish`() = runTest {
        startCounting(dispenseTxn(targetCount = 10))
        setState {
            it.copy(steps = listOf(StepState.VIAL, StepState.CONTAINER_PENDING), currentStep = StepState.CONTAINER_PENDING, targetCount = 5)
        }

        viewModel.onAction(DispenseCountAction.DoneTapped)
        assertTrue(viewModel.uiState.value.showCountMismatchDialog)

        coEvery { dispenseCountRepository.totalPillCount(any()) } returns 10
        viewModel.onAction(DispenseCountAction.CountMismatchConfirmed)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showCountMismatchDialog)
        // Still short on the back count, so the note is asked for.
        assertTrue(viewModel.uiState.value.showNotesDialog)
    }

    // ─────────────────────────── Bottle rescans ───────────────────────────

    private val firstBottle = DispenseBottle(lotNumber = "L1", expirationDate = "04-30-2028", serialNumber = "S1", txnId = 42L)

    /** Counting TARGET_VERIFICATION on NDC1, with a GS1 scan of NDC1 lot [lot]. */
    private fun TestScope.countingWithScanOf(lot: String) {
        startCounting(dispenseTxn(ndc = "NDC1"))
        every { scanDecoder.decode(any(), any()) } returns DecodedScan(
            isGs1 = true, gtin14 = BOTTLE_GTIN, lot = lot, expiry = "04-30-2028", serial = "S1",
        )
        coEvery { dispenseCountRepository.findLocalDrug(BOTTLE_GTIN) } returns DrugMasterEntity(drugId = 1L, ndc = "NDC1")
    }

    @Test
    fun `a bottle scan off the counting steps is ignored`() = runTest {
        startCounting(dispenseTxn(ndc = "NDC1"))
        setState { it.copy(currentStep = StepState.VIAL) }

        viewModel.onAction(DispenseCountAction.BottleScanned("012345", null))
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showAddBottleDialog)
        assertFalse(viewModel.uiState.value.showReplaceBottleDialog)
        coVerify(exactly = 0) { dispenseCountRepository.findLocalDrug(any()) }
    }

    @Test
    fun `a scan of another drug is ignored`() = runTest {
        countingWithScanOf(lot = "L2")
        coEvery { dispenseCountRepository.findLocalDrug(BOTTLE_GTIN) } returns DrugMasterEntity(drugId = 9L, ndc = "OTHER")

        viewModel.onAction(DispenseCountAction.BottleScanned("raw", null))
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showAddBottleDialog)
        coVerify(exactly = 0) { dispenseCountRepository.loadBottles(any()) }
    }

    @Test
    fun `the last bottle scanned again shows the already-scanned toast`() = runTest {
        countingWithScanOf(lot = "L1")
        coEvery { dispenseCountRepository.loadBottles(42L) } returns listOf(firstBottle)
        val effects = collectEffects()

        viewModel.onAction(DispenseCountAction.BottleScanned("raw", null))
        advanceUntilIdle()

        assertEquals(listOf(DispenseCountEffect.ShowToast(R.string.bottle_already_scanned)), effects)
    }

    @Test
    fun `a new bottle after pills were counted asks to add it, and OK saves it`() = runTest {
        countingWithScanOf(lot = "L2")
        coEvery { dispenseCountRepository.loadBottles(42L) } returns listOf(firstBottle)
        coEvery { dispenseCountRepository.totalPillCount(42L) } returns 5

        viewModel.onAction(DispenseCountAction.BottleScanned("raw", "/scans/bottle.jpg"))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.showAddBottleDialog)

        viewModel.onAction(DispenseCountAction.AddBottleConfirmed)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showAddBottleDialog)
        coVerify {
            dispenseCountRepository.addBottle(42L, match { bottle ->
                bottle.lotNumber == "L2" && bottle.txnId == 42L && bottle.barcodeImagePath == "/scans/bottle.jpg"
            })
        }
    }

    @Test
    fun `a new bottle before anything was counted asks to replace, and OK replaces`() = runTest {
        countingWithScanOf(lot = "L2")
        coEvery { dispenseCountRepository.loadBottles(42L) } returns listOf(firstBottle)
        coEvery { dispenseCountRepository.totalPillCount(42L) } returns 0

        viewModel.onAction(DispenseCountAction.BottleScanned("raw", null))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.showReplaceBottleDialog)

        viewModel.onAction(DispenseCountAction.ReplaceBottleConfirmed)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showReplaceBottleDialog)
        coVerify { dispenseCountRepository.replaceLastBottle(42L, match { bottle -> bottle.lotNumber == "L2" }) }
    }

    @Test
    fun `AddBottleConfirmed with no pending scan writes nothing`() = runTest {
        viewModel.onAction(DispenseCountAction.AddBottleConfirmed)
        advanceUntilIdle()

        coVerify(exactly = 0) { dispenseCountRepository.addBottle(any(), any()) }
    }

    @Test
    fun `ReplaceBottleConfirmed with no pending scan writes nothing`() = runTest {
        viewModel.onAction(DispenseCountAction.ReplaceBottleConfirmed)
        advanceUntilIdle()

        coVerify(exactly = 0) { dispenseCountRepository.replaceLastBottle(any(), any()) }
    }

    @Test
    fun `AddBottleDismissed closes the add dialog and drops the scan`() = runTest {
        countingWithScanOf(lot = "L2")
        coEvery { dispenseCountRepository.loadBottles(42L) } returns listOf(firstBottle)
        coEvery { dispenseCountRepository.totalPillCount(42L) } returns 5
        viewModel.onAction(DispenseCountAction.BottleScanned("raw", null))
        advanceUntilIdle()

        viewModel.onAction(DispenseCountAction.AddBottleDismissed)
        viewModel.onAction(DispenseCountAction.AddBottleConfirmed)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showAddBottleDialog)
        coVerify(exactly = 0) { dispenseCountRepository.addBottle(any(), any()) }
    }

    /** Counting with a new bottle in view after pills were counted, so a scan asks to add it. */
    private fun TestScope.countingWithNewBottleInView() {
        countingWithScanOf(lot = "L2")
        coEvery { dispenseCountRepository.loadBottles(42L) } returns listOf(firstBottle)
        coEvery { dispenseCountRepository.totalPillCount(42L) } returns 5
    }

    @Test
    fun `a scan while the add dialog is open is ignored`() = runTest {
        countingWithNewBottleInView()
        viewModel.onAction(DispenseCountAction.BottleScanned("raw", null))
        advanceUntilIdle()

        // The camera keeps decoding the same barcode while the dialog waits.
        viewModel.onAction(DispenseCountAction.BottleScanned("raw", null))
        advanceUntilIdle()

        coVerify(exactly = 1) { dispenseCountRepository.loadBottles(42L) }
    }

    @Test
    fun `a scan after Cancel is handled again`() = runTest {
        countingWithNewBottleInView()
        viewModel.onAction(DispenseCountAction.BottleScanned("raw", null))
        advanceUntilIdle()

        viewModel.onAction(DispenseCountAction.AddBottleDismissed)
        viewModel.onAction(DispenseCountAction.BottleScanned("raw", null))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showAddBottleDialog)
        coVerify(exactly = 2) { dispenseCountRepository.loadBottles(42L) }
    }

    @Test
    fun `ReplaceBottleDismissed closes the replace dialog`() = runTest {
        setState { it.copy(showReplaceBottleDialog = true) }

        viewModel.onAction(DispenseCountAction.ReplaceBottleDismissed)

        assertFalse(viewModel.uiState.value.showReplaceBottleDialog)
    }

    // ─────────────────────────── ADD: frame at the tap ───────────────────────────

    // Dispense always caps at the target. 100 keeps the cap out of the way; the
    // over-target tests set their own first.
    private fun tapAdd(count: Int = 5) {
        if (viewModel.uiState.value.targetCount == 0) setState { it.copy(targetCount = 100) }
        viewModel.onAction(DispenseCountAction.AddTapped(detectedCount = count))
    }

    // drugImagePlaceholder() is outside any try; with stubbed Android its toBitmap() hits a
    // null Bitmap and would kill the save before the insert. No drawable = no placeholder.
    private fun stubSavePath() {
        mockkStatic(ContextCompat::class)
        every { ContextCompat.getDrawable(any(), any()) } returns null
    }

    /** File writes return "/photos/<name>" and the strip drawing is captured. */
    private fun stubPhotoWrites(): io.mockk.CapturingSlot<PhotoInfo> {
        stubSavePath()
        mockkObject(HelperFunctions)
        every { HelperFunctions.saveBitmapToFile(any(), any(), any(), any(), any()) } answers {
            val fileName = thirdArg<String>()
            "/photos/$fileName"
        }
        mockkObject(OverlayUtils)
        val stampedPhotoInfo = slot<PhotoInfo>()
        every {
            OverlayUtils.drawDetectionsOnBitmap(any(), any(), capture(stampedPhotoInfo), any(), any(), any())
        } answers { firstArg() }
        return stampedPhotoInfo
    }

    @Test
    fun `Add with no camera frame shows a toast and records nothing`() = runTest {
        val effects = collectEffects()

        tapAdd()
        advanceUntilIdle()

        assertEquals(listOf(DispenseCountEffect.ShowToast(R.string.add_camera_not_ready)), effects)
        assertFalse(engineFactory.engine.state.value.isAddCooldown)
        // Signature unset, so the retry isn't rejected as a duplicate.
        assertNull(engineFactory.engine.lastAddSignatureForTest())
        coVerify(exactly = 0) { dispenseCountRepository.addCount(any(), any(), any(), any()) }
    }

    @Test
    fun `Add takes the frame at the tap and a newer frame is left alone`() = runTest {
        stubSavePath()
        val tapFrame = mockk<Bitmap>(relaxed = true)
        engineFactory.engine.putFrameForTest(tapFrame)

        tapAdd()
        // Taken synchronously on the tap, before the save coroutine runs.
        assertNull(engineFactory.engine.frameForTest())

        val newerFrame = mockk<Bitmap>(relaxed = true)
        engineFactory.engine.putFrameForTest(newerFrame)

        coVerify(timeout = 3000) { dispenseCountRepository.addCount(any(), any(), any(), any()) }
        verify { tapFrame.recycle() }
        verify(exactly = 0) { newerFrame.recycle() }
        assertEquals(newerFrame, engineFactory.engine.frameForTest())
    }

    @Test
    fun `Add does not wait for a location lookup`() = runTest {
        stubSavePath()
        coEvery { locationProvider.getCurrentLocationAsString() } coAnswers { awaitCancellation() }
        engineFactory.engine.putFrameForTest(mockk<Bitmap>(relaxed = true))

        tapAdd()

        coVerify(timeout = 3000) { dispenseCountRepository.addCount(any(), any(), any(), any()) }
    }

    @Test
    fun `a ready camera starts a location lookup`() = runTest {
        viewModel.onAction(DispenseCountAction.CameraHelperReady(mockk(relaxed = true)))

        coVerify(timeout = 3000) { locationProvider.getCurrentLocationAsString() }
    }

    @Test
    fun `Add over the target is turned down before the engine takes the frame`() = runTest {
        setState { it.copy(targetCount = 3) }
        val frame = mockk<Bitmap>(relaxed = true)
        engineFactory.engine.putFrameForTest(frame)
        val effects = collectEffects()

        tapAdd(count = 5)

        assertEquals(listOf(DispenseCountEffect.ShowToast(R.string.max_count_reached)), effects)
        assertSame(frame, engineFactory.engine.frameForTest())
        assertFalse(engineFactory.engine.state.value.isAddCooldown)
    }

    @Test
    fun `Add over the target during the cooldown shows only the over-target toast`() = runTest {
        // Approved order change: the target cap now runs before the cooldown check.
        stubSavePath()
        setState { it.copy(targetCount = 6) }
        engineFactory.engine.putFrameForTest(mockk(relaxed = true))
        tapAdd(count = 5)                       // 5 <= 6: accepted, cooldown starts
        engineFactory.engine.putFrameForTest(mockk(relaxed = true))
        val effects = collectEffects()

        tapAdd(count = 7)                       // 7 > 6, inside the cooldown

        assertEquals(listOf(DispenseCountEffect.ShowToast(R.string.max_count_reached)), effects)
    }

    @Test
    fun `Add on TARGET_VERIFICATION saves the raw crop with the count`() = runTest {
        stubPhotoWrites()
        selectTxn(9L)
        engineFactory.engine.putFrameForTest(mockk(relaxed = true))

        tapAdd()

        coVerify(timeout = 3000) {
            dispenseCountRepository.addCount(9L, StepState.TARGET_VERIFICATION, 5, match { savedPhoto ->
                savedPhoto.imagePath?.startsWith("/photos/txn_detail_") == true &&
                    savedPhoto.rawImagePath?.endsWith("_raw.jpg") == true
            })
        }
    }

    @Test
    fun `Add on another step saves no raw crop`() = runTest {
        stubPhotoWrites()
        setState { it.copy(currentStep = StepState.CONTAINER_INITIATE) }
        engineFactory.engine.putFrameForTest(mockk(relaxed = true))

        tapAdd()

        coVerify(timeout = 3000) {
            dispenseCountRepository.addCount(any(), StepState.CONTAINER_INITIATE, any(), match { savedPhoto ->
                savedPhoto.imagePath != null && savedPhoto.rawImagePath == null
            })
        }
    }

    @Test
    fun `Add stamps the repository's label on the photo`() = runTest {
        val stampedPhotoInfo = stubPhotoWrites()
        selectTxn(9L)
        coEvery { dispenseCountRepository.countPhotoLabel(9L, StepState.TARGET_VERIFICATION) } returns photoLabel(
            lotNumber = "L1", serialNumber = "S1", expirationDate = "04-30-2028", rxRefill = "123-2",
        )
        engineFactory.engine.putFrameForTest(mockk(relaxed = true))

        tapAdd(count = 7)

        coVerify(timeout = 3000) { dispenseCountRepository.addCount(any(), any(), any(), any()) }
        with(stampedPhotoInfo.captured) {
            assertEquals("L1", lotNumber)
            assertEquals("S1", serialNumber)
            assertEquals("04-30-2028", expirationDate)
            assertEquals("123-2", rxRefill)
            assertEquals("TARGET_VERIFICATION", stepLabel)
            assertEquals(7, count)
            assertFalse(isStockCount)
        }
    }

    // ─────────────────────────── the txn every write lands on ───────────────────────────

    // These use selectTxn, like the other ADD tests: starting the full count would also
    // arm the idle watchdog, which the ADD path doesn't need.

    @Test
    fun `an ADD lands on the txn the flow handed over`() = runTest {
        // R1: an HL7 order saved mid-count used to pull every later write onto itself.
        stubPhotoWrites()
        selectTxn(42L)
        engineFactory.engine.putFrameForTest(mockk(relaxed = true))

        tapAdd()

        coVerify(timeout = 3000) { dispenseCountRepository.addCount(42L, any(), any(), any()) }
        coVerify(exactly = 0) { dispenseCountRepository.addCount(neq(42L), any(), any(), any()) }
    }

    @Test
    fun `an ADD keeps its txn when the stage moves on straight after the tap`() = runTest {
        stubPhotoWrites()
        selectTxn(42L)
        engineFactory.engine.putFrameForTest(mockk(relaxed = true))

        tapAdd()
        viewModel.onAction(DispenseCountAction.StageChanged(DispenseStage.QUEUE, txnId = 0L, isHazardousDrug = false))

        coVerify(timeout = 3000) { dispenseCountRepository.addCount(42L, any(), any(), any()) }
        coVerify(timeout = 3000) { dispenseCountRepository.countPhotoLabel(42L, any()) }
    }

    @Test
    fun `after a queue round trip an ADD lands on the next order`() = runTest {
        stubPhotoWrites()
        selectTxn(42L)
        viewModel.onAction(DispenseCountAction.StageChanged(DispenseStage.QUEUE, txnId = 0L, isHazardousDrug = false))
        selectTxn(43L)
        engineFactory.engine.putFrameForTest(mockk(relaxed = true))

        tapAdd()

        coVerify(timeout = 3000) { dispenseCountRepository.addCount(43L, any(), any(), any()) }
        coVerify(exactly = 0) { dispenseCountRepository.addCount(42L, any(), any(), any()) }
    }
}
