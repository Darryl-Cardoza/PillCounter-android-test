package com.dispensesure.retail.feature.stockCount.loose

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import com.dispensesure.retail.R
import com.dispensesure.retail.core.models.StepState
import com.dispensesure.retail.core.region.Region
import com.dispensesure.retail.core.region.RegionProvider
import com.dispensesure.retail.core.room.models.DrugMasterEntity
import com.dispensesure.retail.core.scanning.data.DrugResolver
import com.dispensesure.retail.core.scanning.domain.model.DecodedScan
import com.dispensesure.retail.core.scanning.domain.model.DrugLookup
import com.dispensesure.retail.core.scanning.engine.CaptureResult
import com.dispensesure.retail.core.scanning.engine.EngineEvent
import com.dispensesure.retail.core.scanning.engine.EngineState
import com.dispensesure.retail.core.scanning.engine.PhotoLabel
import com.dispensesure.retail.core.scanning.engine.PillCountingEngine
import com.dispensesure.retail.core.scanning.engine.SavedPhoto
import com.dispensesure.retail.core.scanning.logic.ScanDecoder
import com.dispensesure.retail.core.tourguide.FtueController
import com.dispensesure.retail.core.tourguide.TourSection
import com.dispensesure.retail.core.tourguide.TutorialData
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import com.dispensesure.retail.feature.stockCount.data.StockCountRepository
import com.dispensesure.retail.navigation.Screen
import com.dispensesure.retail.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.ArrayDeque

private const val CONTAINER_GTIN = "00300930058123"
private const val DRUG_NDC = "00093-0058"

/** Unit tests for [LooseCountViewModel]. The engine is a mock; it has its own tests. */
@OptIn(ExperimentalCoroutinesApi::class)
class LooseCountViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val scanDecoder: ScanDecoder = mockk()
    private val regionProvider: RegionProvider = mockk { every { current } returns Region.US }
    private val drugResolver: DrugResolver = mockk()
    private val stockCountRepository: StockCountRepository = mockk(relaxed = true)
    private val preferenceHelper: PreferenceHelper = mockk(relaxed = true)
    private val engine: PillCountingEngine = mockk(relaxed = true)
    private val engineEvents = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 8)
    private val engineFactory = object : PillCountingEngine.Factory {
        override fun create(scope: CoroutineScope): PillCountingEngine = engine
    }

    private val containerScan = DecodedScan(
        isGs1 = true, gtin14 = CONTAINER_GTIN, lot = "L1", expiry = "12-31-2027", serial = "S1",
    )

    @Before
    fun setup() {
        every { engine.state } returns MutableStateFlow(EngineState())
        every { engine.events } returns engineEvents
        every { engine.lastTenDetections } returns MutableStateFlow(ArrayDeque())
        every { scanDecoder.decode(any(), any()) } returns containerScan
        coEvery { drugResolver.resolve(any(), any(), any(), any(), any(), any()) } returns
            DrugLookup.Found(drug(), fromServer = false)
        every { stockCountRepository.observeCanReset(any()) } returns flowOf(true)
        coEvery {
            stockCountRepository.commitLooseCount(any(), any(), any(), any(), any(), any(), any(), any())
        } returns 7L
    }

    private fun createViewModel(
        batchId: Long = 7L,
        bucketId: String? = "B2",
        allowedNdcs: String = "",
    ) = LooseCountViewModel(
        savedStateHandle = SavedStateHandle(
            mapOf(
                Screen.LooseCount.ARG_BATCH_ID to batchId,
                Screen.LooseCount.ARG_BUCKET_ID to bucketId,
                Screen.LooseCount.ARG_ALLOWED_NDCS to allowedNdcs,
            )
        ),
        scanDecoder = scanDecoder,
        drugResolver = drugResolver,
        stockCountRepository = stockCountRepository,
        preferenceHelper = preferenceHelper,
        engineFactory = engineFactory,
        regionProvider = regionProvider,
    )

    private fun drug(drugType: String? = null, isHazardous: Boolean = false) = DrugMasterEntity(
        drugId = 100L,
        drugName = "Aspirin",
        ndc = DRUG_NDC,
        drugType = drugType,
        isHazardous = isHazardous,
        drugImagePath = "/images/aspirin.png",
    )

    /** Collects every effect the view model sends from here on. */
    private fun TestScope.collectEffects(viewModel: LooseCountViewModel): List<LooseCountEffect> {
        val effects = mutableListOf<LooseCountEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.effects.collect { effect -> effects += effect }
        }
        return effects
    }

    /** A view model whose container scan resolved [scannedDrug]: it is on COUNTING. */
    private fun TestScope.countingViewModel(
        scannedDrug: DrugMasterEntity = drug(),
        batchId: Long = 7L,
    ): LooseCountViewModel {
        coEvery { drugResolver.resolve(any(), any(), any(), any(), any(), any()) } returns
            DrugLookup.Found(scannedDrug, fromServer = false)
        val viewModel = createViewModel(batchId = batchId)
        viewModel.onAction(LooseCountAction.ContainerScanned("RAW"))
        advanceUntilIdle()
        return viewModel
    }

    /** One accepted ADD of [pillCount] pills whose photo is saved at [photoPath]. */
    private fun TestScope.addPills(viewModel: LooseCountViewModel, pillCount: Int, photoPath: String) {
        every { engine.captureCount(pillCount) } returns
            CaptureResult.Captured(pillCount, mockk<Bitmap>(relaxed = true), emptyList(), emptyList())
        coEvery { engine.saveCountPhoto(any(), any(), any()) } returns SavedPhoto(photoPath, null)
        viewModel.onAction(LooseCountAction.AddTapped(pillCount))
        advanceUntilIdle()
    }

    /** Runs what the system runs when the screen leaves the back stack. */
    private fun LooseCountViewModel.clearForTest() {
        LooseCountViewModel::class.java.getDeclaredMethod("onCleared")
            .apply { isAccessible = true }
            .invoke(this)
    }

    // ─────────────────────────── Container scan ───────────────────────────

    @Test
    fun `a container scan resolves the drug and starts counting`() = runTest {
        val viewModel = countingViewModel()

        val state = viewModel.uiState.value
        assertEquals(LooseCountStage.COUNTING, state.stage)
        assertEquals(StepState.TARGET_VERIFICATION, state.currentStep)
        assertEquals(DRUG_NDC, state.drug?.ndc)
        assertEquals("L1", state.lotNumber)
        assertEquals("12-31-2027", state.expirationDate)
        assertEquals("S1", state.serialNumber)
        assertFalse(state.isResolvingContainer)
        coVerify(exactly = 1) {
            drugResolver.resolve(
                code = CONTAINER_GTIN,
                expectedNdc = null,
                nameIfMissing = null,
                nameIfUnknown = null,
                allowNoName = true,
                scannedGtin = CONTAINER_GTIN,
            )
        }
    }

    @Test
    fun `pill detection pauses while the container is looked up`() = runTest {
        countingViewModel()

        verifyOrder {
            engine.pauseDetection()
            engine.resumeDetection()
        }
    }

    @Test
    fun `a hazardous drug turns on the tray check and loads the glove model`() = runTest {
        countingViewModel(scannedDrug = drug(isHazardous = true))

        verify { engine.configureHazardous(true) }
        verify(exactly = 1) { engine.loadGloveModel() }
    }

    @Test
    fun `a non-hazardous drug loads no glove model`() = runTest {
        countingViewModel(scannedDrug = drug(isHazardous = false))

        verify { engine.configureHazardous(false) }
        verify(exactly = 0) { engine.loadGloveModel() }
    }

    @Test
    fun `an Rx label is turned away with the scan-correct-label toast`() = runTest {
        val viewModel = createViewModel()
        val effects = collectEffects(viewModel)

        viewModel.onAction(LooseCountAction.ContainerScanned("RX1|00093-0058|30|B1"))
        advanceUntilIdle()

        assertEquals(listOf(LooseCountEffect.ContainerRejected(R.string.scan_correct_label)), effects)
        assertEquals(LooseCountStage.SCAN_CONTAINER, viewModel.uiState.value.stage)
        coVerify(exactly = 0) { drugResolver.resolve(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a barcode with no GTIN-14 is ignored and scanning resumes`() = runTest {
        every { scanDecoder.decode(any(), any()) } returns containerScan.copy(gtin14 = null, lookupCode = null)
        val viewModel = createViewModel()
        val effects = collectEffects(viewModel)

        viewModel.onAction(LooseCountAction.ContainerScanned("12345"))
        advanceUntilIdle()

        assertEquals(listOf(LooseCountEffect.ContainerRejected(toastText = null)), effects)
        coVerify(exactly = 0) { drugResolver.resolve(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a drug outside the PMS allowlist is turned away with its NDC`() = runTest {
        val viewModel = createViewModel(allowedNdcs = "OTHER-1,OTHER-2")
        val effects = collectEffects(viewModel)

        viewModel.onAction(LooseCountAction.ContainerScanned("RAW"))
        advanceUntilIdle()

        assertEquals(
            listOf(LooseCountEffect.ContainerRejected(R.string.batch_stock_count_ndc_not_in_request, DRUG_NDC)),
            effects,
        )
        assertEquals(LooseCountStage.SCAN_CONTAINER, viewModel.uiState.value.stage)
    }

    @Test
    fun `an unknown drug on a PMS batch is turned away with the scanned GTIN`() = runTest {
        coEvery {
            drugResolver.resolve(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        } returns DrugLookup.NotFound
        val viewModel = createViewModel(allowedNdcs = DRUG_NDC)
        val effects = collectEffects(viewModel)

        viewModel.onAction(LooseCountAction.ContainerScanned("RAW"))
        advanceUntilIdle()

        assertEquals(
            listOf(LooseCountEffect.ContainerRejected(R.string.batch_stock_count_ndc_not_in_request, CONTAINER_GTIN)),
            effects,
        )
    }

    @Test
    fun `a drug on the PMS allowlist starts counting`() = runTest {
        // Blanks and spaces in the route value are dropped.
        val viewModel = createViewModel(allowedNdcs = " $DRUG_NDC , ,PMS-2")

        viewModel.onAction(LooseCountAction.ContainerScanned("RAW"))
        advanceUntilIdle()

        assertEquals(LooseCountStage.COUNTING, viewModel.uiState.value.stage)
    }

    @Test
    fun `an unknown drug is turned away with the NDC-not-found toast`() = runTest {
        coEvery {
            drugResolver.resolve(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        } returns DrugLookup.NotFound
        val viewModel = createViewModel()
        val effects = collectEffects(viewModel)

        viewModel.onAction(LooseCountAction.ContainerScanned("RAW"))
        advanceUntilIdle()

        assertEquals(listOf(LooseCountEffect.ContainerRejected(R.string.batch_stock_count_drug_not_found, CONTAINER_GTIN)), effects)
    }

    @Test
    fun `a failed lookup is turned away with the scan-failed toast`() = runTest {
        coEvery { drugResolver.resolve(any(), any(), any(), any(), any(), any()) } returns
            DrugLookup.Failed(RuntimeException("offline"))
        val viewModel = createViewModel()
        val effects = collectEffects(viewModel)

        viewModel.onAction(LooseCountAction.ContainerScanned("RAW"))
        advanceUntilIdle()

        assertEquals(listOf(LooseCountEffect.ContainerRejected(R.string.batch_stock_count_scan_failed)), effects)
        assertFalse(viewModel.uiState.value.isResolvingContainer)
    }

    @Test
    fun `a failed lookup on a PMS batch shows the scan-failed toast, not the allowlist one`() =
        runTest {
            coEvery { drugResolver.resolve(any(), any(), any(), any(), any(), any()) } returns
                DrugLookup.Failed(RuntimeException("offline"))
            val viewModel = createViewModel(allowedNdcs = DRUG_NDC)
            val effects = collectEffects(viewModel)

            viewModel.onAction(LooseCountAction.ContainerScanned("RAW"))
            advanceUntilIdle()

            assertEquals(
                listOf(LooseCountEffect.ContainerRejected(R.string.batch_stock_count_scan_failed)),
                effects,
            )
        }

    // ─────────────────────────── ADD ───────────────────────────

    @Test
    fun `ADD keeps the count in memory with its photo`() = runTest {
        val viewModel = countingViewModel()

        addPills(viewModel, pillCount = 5, photoPath = "/photos/1.jpg")

        val entry = viewModel.uiState.value.entries.single()
        assertEquals(5, entry.pillCount)
        assertEquals("/photos/1.jpg", entry.imagePath)
        assertEquals(5, viewModel.uiState.value.totalCount)
        coVerify(exactly = 0) {
            stockCountRepository.commitLooseCount(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `the ADD photo is labelled as a stock count with the scanned bottle and no Rx`() = runTest {
        val viewModel = countingViewModel()

        addPills(viewModel, pillCount = 5, photoPath = "/photos/1.jpg")

        val photoLabel = slot<PhotoLabel>()
        coVerify { engine.saveCountPhoto(any(), capture(photoLabel), keepRawCrop = false) }
        assertTrue(photoLabel.captured.isStockCount)
        assertNull(photoLabel.captured.rxRefill)
        assertEquals("Aspirin", photoLabel.captured.drugName)
        assertEquals(DRUG_NDC, photoLabel.captured.requestedNdc)
        assertEquals(DRUG_NDC, photoLabel.captured.dispensedNdc)
        assertEquals("L1", photoLabel.captured.lotNumber)
        assertEquals("S1", photoLabel.captured.serialNumber)
        assertEquals("12-31-2027", photoLabel.captured.expirationDate)
        assertEquals(StepState.TARGET_VERIFICATION.name, photoLabel.captured.stepLabel)
    }

    @Test
    fun `an ADD the engine turns down shows its toast and keeps nothing`() = runTest {
        val viewModel = countingViewModel()
        val effects = collectEffects(viewModel)
        every { engine.captureCount(5) } returns CaptureResult.Rejected(CaptureResult.Reason.Duplicate)

        viewModel.onAction(LooseCountAction.AddTapped(5))
        advanceUntilIdle()

        assertEquals(listOf(LooseCountEffect.ShowToast(R.string.duplicate_scan_ignored)), effects)
        assertTrue(viewModel.uiState.value.entries.isEmpty())
        coVerify(exactly = 0) { engine.saveCountPhoto(any(), any(), any()) }
    }

    @Test
    fun `ADD before the container is scanned does nothing`() = runTest {
        val viewModel = createViewModel()

        viewModel.onAction(LooseCountAction.AddTapped(5))
        advanceUntilIdle()

        verify(exactly = 0) { engine.captureCount(any()) }
    }

    // ─────────────────────────── View All delete ───────────────────────────

    @Test
    fun `deleting an entry drops it and its photo`() = runTest {
        val viewModel = countingViewModel()
        addPills(viewModel, pillCount = 5, photoPath = "/photos/1.jpg")
        addPills(viewModel, pillCount = 3, photoPath = "/photos/2.jpg")
        val firstEntryId = viewModel.uiState.value.entries.first().entryId

        viewModel.onAction(LooseCountAction.EntryDeleted(firstEntryId))

        assertEquals(listOf("/photos/2.jpg"), viewModel.uiState.value.entries.map { entry -> entry.imagePath })
        assertEquals(3, viewModel.uiState.value.totalCount)
        verify { stockCountRepository.deletePhotos(listOf("/photos/1.jpg")) }
    }

    @Test
    fun `deleting every entry one by one empties the list`() = runTest {
        val viewModel = countingViewModel()
        addPills(viewModel, pillCount = 5, photoPath = "/photos/1.jpg")
        addPills(viewModel, pillCount = 3, photoPath = "/photos/2.jpg")
        addPills(viewModel, pillCount = 2, photoPath = "/photos/3.jpg")
        val entryIds = viewModel.uiState.value.entries.map { entry -> entry.entryId }

        // View All's select-all deletes the ids it captured, in order.
        entryIds.forEach { entryId -> viewModel.onAction(LooseCountAction.EntryDeleted(entryId)) }

        assertTrue(viewModel.uiState.value.entries.isEmpty())
    }

    // ─────────────────────────── Done and save ───────────────────────────

    @Test
    fun `Done with nothing counted shows the no-transaction toast and no dialog`() = runTest {
        val viewModel = countingViewModel()
        val effects = collectEffects(viewModel)

        viewModel.onAction(LooseCountAction.DoneTapped)
        advanceUntilIdle()

        assertEquals(listOf(LooseCountEffect.ShowToast(R.string.no_transaction_found)), effects)
        assertFalse(viewModel.uiState.value.showEndCountDialog)
    }

    @Test
    fun `Done with pills asks to end the count`() = runTest {
        val viewModel = countingViewModel()
        addPills(viewModel, pillCount = 5, photoPath = "/photos/1.jpg")

        viewModel.onAction(LooseCountAction.DoneTapped)

        assertTrue(viewModel.uiState.value.showEndCountDialog)
    }

    @Test
    fun `No closes the dialog and resumes from idle`() = runTest {
        val viewModel = countingViewModel()
        addPills(viewModel, pillCount = 5, photoPath = "/photos/1.jpg")
        viewModel.onAction(LooseCountAction.DoneTapped)

        viewModel.onAction(LooseCountAction.EndCountDismissed)

        assertFalse(viewModel.uiState.value.showEndCountDialog)
        verify { engine.resumeFromIdle() }
    }

    @Test
    fun `Yes saves the total once and reports the batch`() = runTest {
        val viewModel = countingViewModel()
        val effects = collectEffects(viewModel)
        addPills(viewModel, pillCount = 5, photoPath = "/photos/1.jpg")
        addPills(viewModel, pillCount = 3, photoPath = "/photos/2.jpg")
        viewModel.onAction(LooseCountAction.DoneTapped)

        viewModel.onAction(LooseCountAction.EndCountConfirmed)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            stockCountRepository.commitLooseCount(
                batchId = 7L,
                bucketId = "B2",
                drugId = 100L,
                lotNumber = "L1",
                expirationDate = "12-31-2027",
                serialNumber = "S1",
                loosePills = 8,
                controlledImagePaths = null,
            )
        }
        assertEquals(listOf(LooseCountEffect.CountSaved(7L)), effects)
        assertTrue(viewModel.uiState.value.entries.isEmpty())
        assertFalse(viewModel.uiState.value.showEndCountDialog)
        assertFalse(viewModel.uiState.value.isSaving)
    }

    @Test
    fun `a new batch is saved with the picked bucket`() = runTest {
        val viewModel = countingViewModel(batchId = 0L)
        addPills(viewModel, pillCount = 5, photoPath = "/photos/1.jpg")
        viewModel.onAction(LooseCountAction.DoneTapped)

        viewModel.onAction(LooseCountAction.EndCountConfirmed)
        advanceUntilIdle()

        coVerify { stockCountRepository.commitLooseCount(0L, "B2", any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `during the tour a new batch is saved in the tutorial bucket`() = runTest {
        val viewModel = countingViewModel(batchId = 0L)
        addPills(viewModel, pillCount = 5, photoPath = "/photos/1.jpg")
        viewModel.onAction(LooseCountAction.DoneTapped)
        FtueController.start(listOf(TourSection.DISPENSE), hl7Enabled = false, notesEnabled = false)

        try {
            viewModel.onAction(LooseCountAction.EndCountConfirmed)
            advanceUntilIdle()
        } finally {
            FtueController.end() // process-wide singleton: don't leak the tour into other tests
        }

        coVerify {
            stockCountRepository.commitLooseCount(
                0L,
                TutorialData.BUCKET,
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        }
    }

    @Test
    fun `an ADD still saving its photo is in the saved total`() = runTest {
        val viewModel = countingViewModel()
        addPills(viewModel, pillCount = 5, photoPath = "/photos/1.jpg")
        val photoSaved = CompletableDeferred<SavedPhoto>()
        every { engine.captureCount(3) } returns
            CaptureResult.Captured(3, mockk<Bitmap>(relaxed = true), emptyList(), emptyList())
        coEvery { engine.saveCountPhoto(any(), any(), any()) } coAnswers { photoSaved.await() }
        viewModel.onAction(LooseCountAction.AddTapped(3))
        viewModel.onAction(LooseCountAction.DoneTapped)

        viewModel.onAction(LooseCountAction.EndCountConfirmed)
        advanceUntilIdle()
        photoSaved.complete(SavedPhoto("/photos/2.jpg", null))
        advanceUntilIdle()

        coVerify(exactly = 1) {
            stockCountRepository.commitLooseCount(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                loosePills = 8,
                any(),
            )
        }
    }

    @Test
    fun `a non-controlled count deletes its photos once saved`() = runTest {
        val viewModel = countingViewModel(scannedDrug = drug(drugType = null))
        addPills(viewModel, pillCount = 5, photoPath = "/photos/1.jpg")
        addPills(viewModel, pillCount = 3, photoPath = "/photos/2.jpg")
        viewModel.onAction(LooseCountAction.DoneTapped)

        viewModel.onAction(LooseCountAction.EndCountConfirmed)
        advanceUntilIdle()

        verify { stockCountRepository.deletePhotos(listOf("/photos/1.jpg", "/photos/2.jpg")) }
    }

    @Test
    fun `a controlled count keeps its photos on the saved line`() = runTest {
        val viewModel = countingViewModel(scannedDrug = drug(drugType = "CII"))
        addPills(viewModel, pillCount = 5, photoPath = "/photos/1.jpg")
        viewModel.onAction(LooseCountAction.DoneTapped)

        viewModel.onAction(LooseCountAction.EndCountConfirmed)
        advanceUntilIdle()

        coVerify {
            stockCountRepository.commitLooseCount(
                any(), any(), any(), any(), any(), any(), any(), controlledImagePaths = listOf("/photos/1.jpg"),
            )
        }
        verify(exactly = 0) { stockCountRepository.deletePhotos(any()) }
    }

    @Test
    fun `a failed save keeps the counts and shows the save-failed toast`() = runTest {
        coEvery {
            stockCountRepository.commitLooseCount(any(), any(), any(), any(), any(), any(), any(), any())
        } throws IllegalStateException("disk full")
        val viewModel = countingViewModel()
        val effects = collectEffects(viewModel)
        addPills(viewModel, pillCount = 5, photoPath = "/photos/1.jpg")
        viewModel.onAction(LooseCountAction.DoneTapped)

        viewModel.onAction(LooseCountAction.EndCountConfirmed)
        advanceUntilIdle()

        assertEquals(listOf(LooseCountEffect.ShowToast(R.string.batch_stock_count_save_failed)), effects)
        assertEquals(1, viewModel.uiState.value.entries.size)
        assertFalse(viewModel.uiState.value.isSaving)
        verify(exactly = 0) { stockCountRepository.deletePhotos(any()) }
    }

    // ─────────────────────────── Reset ───────────────────────────

    @Test
    fun `Reset drops the counts and their photos and goes back to the container scan`() = runTest {
        val viewModel = countingViewModel()
        addPills(viewModel, pillCount = 5, photoPath = "/photos/1.jpg")

        viewModel.onAction(LooseCountAction.ResetConfirmed)

        val state = viewModel.uiState.value
        assertEquals(LooseCountStage.SCAN_CONTAINER, state.stage)
        assertNull(state.drug)
        assertNull(state.lotNumber)
        assertTrue(state.entries.isEmpty())
        verify { stockCountRepository.deletePhotos(listOf("/photos/1.jpg")) }
        verify { engine.clearDetectedPills() }
    }

    @Test
    fun `Reset follows the batch's sync state`() = runTest {
        every { stockCountRepository.observeCanReset(7L) } returns flowOf(false)

        val viewModel = createViewModel(batchId = 7L)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.canReset)
    }

    // ─────────────────────────── Leaving ───────────────────────────

    @Test
    fun `leaving drops the unsaved photos and closes the engine`() = runTest {
        val viewModel = countingViewModel()
        addPills(viewModel, pillCount = 5, photoPath = "/photos/1.jpg")

        viewModel.clearForTest()

        verify { stockCountRepository.deletePhotos(listOf("/photos/1.jpg")) }
        verify { engine.close() }
    }

    @Test
    fun `leaving after a save keeps the saved photos`() = runTest {
        val viewModel = countingViewModel(scannedDrug = drug(drugType = "CII"))
        addPills(viewModel, pillCount = 5, photoPath = "/photos/1.jpg")
        viewModel.onAction(LooseCountAction.DoneTapped)
        viewModel.onAction(LooseCountAction.EndCountConfirmed)
        advanceUntilIdle()

        viewModel.clearForTest()

        verify(exactly = 0) { stockCountRepository.deletePhotos(listOf("/photos/1.jpg")) }
    }

    // ─────────────────────────── Engine and screen plumbing ───────────────────────────

    @Test
    fun `a tray warning from the engine is shown as a toast`() = runTest {
        val viewModel = createViewModel()
        val effects = collectEffects(viewModel)
        advanceUntilIdle()

        engineEvents.emit(EngineEvent.Warning(R.string.hazardous_tray_warning))
        advanceUntilIdle()

        assertEquals(listOf(LooseCountEffect.ShowToast(R.string.hazardous_tray_warning)), effects)
    }

    @Test
    fun `View All pauses the camera and the idle timer, closing it resumes both`() = runTest {
        val viewModel = createViewModel()

        viewModel.onAction(LooseCountAction.HistoryVisibilityChanged(isShown = true))
        viewModel.onAction(LooseCountAction.HistoryVisibilityChanged(isShown = false))

        verifyOrder {
            engine.stopIdleTimer()
            engine.setCameraPaused(true)
            engine.armIdleTimer()
            engine.setCameraPaused(false)
        }
    }

    @Test
    fun `the engine starts with gloves reset`() = runTest {
        createViewModel()

        verify { engine.resetGloves() }
    }

    @Test
    fun `canada container scan is looked up by the DIN and saves no GTIN`() = runTest {
        every { regionProvider.current } returns Region.CA
        every { scanDecoder.decode(any(), Region.CA) } returns
            DecodedScan(isGs1 = false, gtin14 = null, lot = null, expiry = null, serial = null, lookupCode = "00013285")
        coEvery { drugResolver.resolve(any(), any(), any(), any(), any(), any()) } returns
            DrugLookup.Found(drug(), fromServer = true)
        val viewModel = createViewModel()

        viewModel.onAction(LooseCountAction.ContainerScanned("00013285"))
        advanceUntilIdle()

        coVerify {
            drugResolver.resolve(
                code = "00013285",
                expectedNdc = null,
                allowNoName = true,
                nameIfUnknown = null,
                scannedGtin = null,
            )
        }
    }
}
