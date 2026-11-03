package com.dispensesure.retail.feature.dispense.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.dispensesure.retail.R
import com.dispensesure.retail.core.room.models.DispenseTxnEntity
import com.dispensesure.retail.core.room.models.DrugMasterEntity
import com.dispensesure.retail.core.room.models.dtos.DispenseTxnWithDrug
import com.dispensesure.retail.core.room.models.enums.CountStatus
import com.dispensesure.retail.core.room.models.enums.TxnPriority
import com.dispensesure.retail.core.scanning.domain.model.DispenseBottle
import com.dispensesure.retail.core.tourguide.FtueController
import com.dispensesure.retail.core.tourguide.TourSection
import com.dispensesure.retail.core.utils.common.ParsedScanData
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import com.dispensesure.retail.feature.dashboard.domain.model.KpiFilter
import com.dispensesure.retail.feature.dashboard.domain.model.QueueItem
import com.dispensesure.retail.feature.dispense.data.ContainerOutcome
import com.dispensesure.retail.feature.dispense.data.ContainerVerifier
import com.dispensesure.retail.feature.dispense.data.DispenseFlowRepository
import com.dispensesure.retail.feature.dispense.data.RxLabelResolver
import com.dispensesure.retail.feature.dispense.data.RxScanOutcome
import com.dispensesure.retail.feature.dispense.domain.model.DispenseFlowAction
import com.dispensesure.retail.feature.dispense.domain.model.DispenseFlowEffect
import com.dispensesure.retail.feature.dispense.domain.model.DispenseStage
import com.dispensesure.retail.feature.dispense.domain.model.StandaloneRxDraft
import com.dispensesure.retail.navigation.Screen
import com.dispensesure.retail.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

private const val RAW_RX_LABEL = "RX999|NDC123|10|B1"
private const val GTIN = "00368540100014"
private const val SERVER_IMAGE_URL = "https://images.example/drug.png"

@OptIn(ExperimentalCoroutinesApi::class)
class DispenseFlowViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val preferenceHelper: PreferenceHelper = mockk(relaxed = true)
    private val rxLabelResolver: RxLabelResolver = mockk()
    private val containerVerifier: ContainerVerifier = mockk()
    private val dispenseFlowRepository: DispenseFlowRepository = mockk(relaxed = true)
    private val firstBottle = DispenseBottle(lotNumber = "LOT1", expirationDate = "01-31-2030", serialNumber = "SN1")

    @Before
    fun setUp() {
        every { preferenceHelper.getLocalId() } returns 1L
        coEvery { dispenseFlowRepository.loadTxn(any()) } returns null
        coEvery { dispenseFlowRepository.loadDrug(any()) } returns null
        coEvery { dispenseFlowRepository.loadOrderedDrug(any()) } returns null
        coEvery { dispenseFlowRepository.markContainerVerified(any(), any(), any(), any()) } returns true
        coEvery { dispenseFlowRepository.createStandaloneTxn(any(), any()) } returns 200L
        coEvery { dispenseFlowRepository.hasPendingDispenses(any()) } returns false
        every { dispenseFlowRepository.observeQueue(any()) } returns flowOf(emptyList())
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun createViewModel(
        txnId: Long = 0L,
        fromHl7: Boolean = false,
        fromQueue: Boolean = false,
    ) = DispenseFlowViewModel(
        savedStateHandle = SavedStateHandle(
            mapOf(
                Screen.DispenseFlow.ARG_TXN_ID to txnId,
                Screen.DispenseFlow.ARG_FROM_HL7 to fromHl7,
                Screen.DispenseFlow.ARG_FROM_QUEUE to fromQueue,
            )
        ),
        preferenceHelper = preferenceHelper,
        rxLabelResolver = rxLabelResolver,
        containerVerifier = containerVerifier,
        dispenseFlowRepository = dispenseFlowRepository,
    )

    private fun drug(
        drugId: Long = 10L,
        drugName: String? = "Aspirin",
        ndc: String = "NDC123",
        packageQty: Int? = 5,
        isHazardous: Boolean = false,
        drugType: String? = "CII",
        strength: String? = null,
        dosageForm: String? = null,
        drugImagePath: String? = null,
    ) = DrugMasterEntity(
        drugId = drugId, drugName = drugName, ndc = ndc, packageQty = packageQty,
        isHazardous = isHazardous, drugType = drugType, strength = strength,
        dosageForm = dosageForm, drugImagePath = drugImagePath,
    )

    private fun txn(
        txnId: Long = 7L,
        drugId: Long? = 10L,
        isNdcVerified: Boolean? = false,
        rxNo: String? = "RX999",
        targetCount: Int? = 10,
        bucketId: String? = null,
    ) = DispenseTxnEntity(
        txnId = txnId, drugId = drugId, status = CountStatus.PARTIAL, isNdcVerified = isNdcVerified,
        rxNo = rxNo, targetCount = targetCount, bucketId = bucketId,
    )

    private fun label(rxNo: String? = "RX999", qty: String? = "10", bucket: String? = "B1") =
        ParsedScanData(rxNo = rxNo, ndcNo = "NDC123", qty = qty, bucket = bucket)

    /** Collects every effect the view model sends from here on. */
    private fun TestScope.collectEffects(viewModel: DispenseFlowViewModel): List<DispenseFlowEffect> {
        val effects = mutableListOf<DispenseFlowEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.effects.collect { effect -> effects += effect }
        }
        return effects
    }

    /** Txn 7 picked up on the container step, as a resumed dispense lands. */
    private fun TestScope.containerStepViewModel(orderedDrug: DrugMasterEntity? = drug()): DispenseFlowViewModel {
        coEvery { dispenseFlowRepository.loadTxn(7L) } returns txn(isNdcVerified = false)
        coEvery { dispenseFlowRepository.loadDrug(10L) } returns orderedDrug
        val viewModel = createViewModel(txnId = 7L)
        advanceUntilIdle()
        return viewModel
    }

    /** Txn 7 (Rx RX999) counting, as a resumed, container-verified dispense lands. */
    private fun TestScope.countingViewModel(): DispenseFlowViewModel {
        coEvery { dispenseFlowRepository.loadTxn(7L) } returns txn(isNdcVerified = true)
        coEvery { dispenseFlowRepository.loadDrug(10L) } returns drug()
        val viewModel = createViewModel(txnId = 7L)
        advanceUntilIdle()
        return viewModel
    }

    private fun TestScope.scanRxLabel(viewModel: DispenseFlowViewModel, outcome: RxScanOutcome) {
        coEvery { rxLabelResolver.resolve(any()) } returns outcome
        viewModel.onAction(DispenseFlowAction.RxLabelScanned(RAW_RX_LABEL))
        advanceUntilIdle()
    }

    private fun TestScope.scanContainer(viewModel: DispenseFlowViewModel, outcome: ContainerOutcome) {
        coEvery { containerVerifier.verify(any(), any()) } returns outcome
        viewModel.onAction(DispenseFlowAction.ContainerScanned(GTIN, firstBottle))
        advanceUntilIdle()
    }

    // ───────────────────────────── entry ─────────────────────────────

    @Test
    fun `a plain entry starts on the Rx step without waiting`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(DispenseStage.PRE_RX, viewModel.uiState.value.stage)
        assertFalse(viewModel.uiState.value.isResolvingEntry)
        assertFalse(viewModel.uiState.value.isFromQueue)
    }

    @Test
    fun `an HL7 or resume entry waits until its stage is known`() = runTest {
        assertTrue(createViewModel(fromHl7 = true).uiState.value.isResolvingEntry)
        assertTrue(createViewModel(txnId = 7L).uiState.value.isResolvingEntry)
    }

    @Test
    fun `an HL7 entry with no txn id falls back to the Rx step`() = runTest {
        val viewModel = createViewModel(fromHl7 = true)
        advanceUntilIdle()

        assertEquals(DispenseStage.PRE_RX, viewModel.uiState.value.stage)
        assertFalse(viewModel.uiState.value.isResolvingEntry)
    }

    @Test
    fun `an HL7 entry with a missing txn falls back to the Rx step`() = runTest {
        val viewModel = createViewModel(txnId = 7L, fromHl7 = true)
        advanceUntilIdle()

        assertEquals(DispenseStage.PRE_RX, viewModel.uiState.value.stage)
        assertFalse(viewModel.uiState.value.isResolvingEntry)
    }

    @Test
    fun `an HL7 entry loads the order onto the container step`() = runTest {
        coEvery { dispenseFlowRepository.loadTxn(7L) } returns txn(bucketId = "B9")
        coEvery { dispenseFlowRepository.loadDrug(10L) } returns drug(isHazardous = true, drugImagePath = "/images/aspirin.webp")

        val viewModel = createViewModel(txnId = 7L, fromHl7 = true)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(DispenseStage.PRE_NDC, state.stage)
        assertFalse(state.isResolvingEntry)
        assertEquals(7L, state.txnId)
        assertEquals("Aspirin", state.drugName)
        assertEquals("NDC123", state.hl7ExpectedNdc)
        assertEquals("RX999", state.rxNo)
        assertEquals("10", state.qty)
        assertEquals("/images/aspirin.webp", state.drugImage)
        assertEquals("B9", state.selectedBucketId)
        assertTrue(state.isHazardous)
    }

    @Test
    fun `an HL7 entry with no drug keeps the drug fields empty`() = runTest {
        coEvery { dispenseFlowRepository.loadTxn(7L) } returns txn(drugId = null)

        val viewModel = createViewModel(txnId = 7L, fromHl7 = true)
        advanceUntilIdle()

        assertEquals(DispenseStage.PRE_NDC, viewModel.uiState.value.stage)
        assertNull(viewModel.uiState.value.hl7ExpectedNdc)
        assertEquals("", viewModel.uiState.value.drugName)
    }

    @Test
    fun `a resume entry with a missing txn falls back to the Rx step`() = runTest {
        val viewModel = createViewModel(txnId = 7L)
        advanceUntilIdle()

        assertEquals(DispenseStage.PRE_RX, viewModel.uiState.value.stage)
        assertFalse(viewModel.uiState.value.isResolvingEntry)
    }

    @Test
    fun `a resume entry with the container verified goes straight to counting`() = runTest {
        val viewModel = countingViewModel()

        assertEquals(DispenseStage.COUNTING, viewModel.uiState.value.stage)
        assertEquals("RX999", viewModel.uiState.value.rxNo)
        coVerify { dispenseFlowRepository.clearGlovesPresent(7L) }
    }

    @Test
    fun `a resume entry straight to counting loads the order's NDC, bucket and details`() = runTest {
        coEvery { dispenseFlowRepository.loadTxn(7L) } returns txn(isNdcVerified = true, bucketId = "B7")
        coEvery { dispenseFlowRepository.loadDrug(10L) } returns
            drug(strength = "10 mg", dosageForm = "Tablet", drugImagePath = "/images/aspirin.webp")

        val viewModel = createViewModel(txnId = 7L)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(DispenseStage.COUNTING, state.stage)
        assertEquals("NDC123", state.hl7ExpectedNdc)
        assertEquals("B7", state.selectedBucketId)
        assertEquals("/images/aspirin.webp", state.drugImage)
        assertEquals("10 mg", state.ndcStrength)
        assertEquals("Tablet", state.ndcDosageForm)
    }

    @Test
    fun `a resume entry with the container pending lands on the container step`() = runTest {
        val viewModel = containerStepViewModel()

        assertEquals(DispenseStage.PRE_NDC, viewModel.uiState.value.stage)
        assertEquals("NDC123", viewModel.uiState.value.hl7ExpectedNdc)
        assertFalse(viewModel.uiState.value.isResolvingEntry)
    }

    @Test
    fun `a queue entry opens the queue on the pending tab`() = runTest {
        val viewModel = createViewModel(fromQueue = true)
        advanceUntilIdle()

        assertEquals(DispenseStage.QUEUE, viewModel.uiState.value.stage)
        assertEquals(KpiFilter.DISP_PENDING, viewModel.uiState.value.selectedQueueFilter)
        assertTrue(viewModel.uiState.value.isFromQueue)
        assertFalse(viewModel.uiState.value.isResolvingEntry)
    }

    @Test
    fun `a queue entry that resumes a txn skips the queue`() = runTest {
        coEvery { dispenseFlowRepository.loadTxn(7L) } returns txn(isNdcVerified = false)

        val viewModel = createViewModel(txnId = 7L, fromQueue = true)
        advanceUntilIdle()

        assertEquals(DispenseStage.PRE_NDC, viewModel.uiState.value.stage)
        assertTrue(viewModel.uiState.value.isFromQueue)
    }

    @Test
    fun `an HL7 entry with no txn id loads nothing`() = runTest {
        val viewModel = createViewModel(fromHl7 = true)
        advanceUntilIdle()

        assertEquals(0L, viewModel.uiState.value.txnId)
        coVerify(exactly = 0) { dispenseFlowRepository.loadTxn(any()) }
    }

    // ───────────────────────────── Rx label ─────────────────────────────

    @Test
    fun `an Rx label on the container step is ignored`() = runTest {
        val viewModel = containerStepViewModel()

        viewModel.onAction(DispenseFlowAction.RxLabelScanned(RAW_RX_LABEL))
        advanceUntilIdle()

        coVerify(exactly = 0) { rxLabelResolver.resolve(any()) }
    }

    @Test
    fun `an Rx label on the queue moves to the Rx step and is read`() = runTest {
        val viewModel = createViewModel(fromQueue = true)
        advanceUntilIdle()

        scanRxLabel(viewModel, RxScanOutcome.NotFound)

        assertEquals(DispenseStage.PRE_RX, viewModel.uiState.value.stage)
        coVerify { rxLabelResolver.resolve(RAW_RX_LABEL) }
    }

    @Test
    fun `a second Rx label while one is loading is dropped`() = runTest {
        coEvery { rxLabelResolver.resolve(any()) } returns RxScanOutcome.NotFound
        val viewModel = createViewModel()

        viewModel.onAction(DispenseFlowAction.RxLabelScanned(RAW_RX_LABEL))
        viewModel.onAction(DispenseFlowAction.RxLabelScanned(RAW_RX_LABEL))
        advanceUntilIdle()

        coVerify(exactly = 1) { rxLabelResolver.resolve(any()) }
    }

    @Test
    fun `an invalid Rx label toasts and reads again at once`() = runTest {
        val viewModel = createViewModel()
        val effects = collectEffects(viewModel)

        scanRxLabel(viewModel, RxScanOutcome.Invalid)

        assertEquals(listOf(DispenseFlowEffect.ScanRejected(R.string.scan_correct_label, pauseScanning = false)), effects)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `each turned-away Rx label toasts and pauses scanning`() = runTest {
        val toastByOutcome = mapOf(
            RxScanOutcome.AlreadyCompleted to R.string.rx_already_completed_message,
            RxScanOutcome.NotFound to R.string.transaction_not_found_toast,
            RxScanOutcome.DrugNotFound to R.string.rx_label_drug_not_found_toast,
            RxScanOutcome.NdcMismatch to R.string.rescan_ndc_does_not_match_toast,
        )
        toastByOutcome.forEach { (outcome, toastText) ->
            val viewModel = createViewModel()
            val effects = collectEffects(viewModel)

            scanRxLabel(viewModel, outcome)

            assertEquals(listOf(DispenseFlowEffect.ScanRejected(toastText, pauseScanning = true)), effects)
            assertFalse(viewModel.uiState.value.isLoading)
            assertFalse(viewModel.uiState.value.showRxDetails)
            assertEquals(DispenseStage.PRE_RX, viewModel.uiState.value.stage)
        }
        // A rejected label must not prep any txn.
        coVerify(exactly = 0) { dispenseFlowRepository.clearGlovesPresent(any()) }
    }

    @Test
    fun `an order on hold shows its dialog until dismissed`() = runTest {
        val viewModel = createViewModel()

        scanRxLabel(viewModel, RxScanOutcome.OnHold)
        assertTrue(viewModel.uiState.value.showOnHoldDialog)
        assertFalse(viewModel.uiState.value.isLoading)

        viewModel.onAction(DispenseFlowAction.OnHoldDismissed)
        assertFalse(viewModel.uiState.value.showOnHoldDialog)
    }

    @Test
    fun `a partial order shows the Rx sheet and preps the txn`() = runTest {
        val viewModel = createViewModel()

        scanRxLabel(viewModel, RxScanOutcome.Resume(txn(txnId = 1L), drug(), label()))

        val state = viewModel.uiState.value
        assertEquals(DispenseStage.PRE_RX, state.stage)
        assertTrue(state.showRxDetails)
        assertEquals(DispenseStage.PRE_NDC, state.pendingRxResumeStage)
        assertEquals(1L, state.txnId)
        assertEquals("Aspirin", state.drugName)
        assertEquals("NDC123", state.hl7ExpectedNdc)
        assertEquals("10", state.qty)
        assertFalse(state.isLoading)
        coVerify { dispenseFlowRepository.clearGlovesPresent(1L) }
    }

    @Test
    fun `a partial order with the container verified goes straight to counting`() = runTest {
        val viewModel = createViewModel()

        scanRxLabel(viewModel, RxScanOutcome.Resume(txn(txnId = 1L, isNdcVerified = true), drug(), label()))

        assertEquals(DispenseStage.COUNTING, viewModel.uiState.value.stage)
        assertFalse(viewModel.uiState.value.showRxDetails)
        assertNull(viewModel.uiState.value.pendingRxResumeStage)
    }

    @Test
    fun `a partial order falls back to the label for what it leaves blank`() = runTest {
        val viewModel = createViewModel()

        scanRxLabel(
            viewModel,
            RxScanOutcome.Resume(txn(rxNo = null, targetCount = null, bucketId = null), drug(), label(rxNo = "RX999", qty = "12", bucket = "B1")),
        )

        assertEquals("RX999", viewModel.uiState.value.rxNo)
        assertEquals("12", viewModel.uiState.value.qty)
        assertEquals("B1", viewModel.uiState.value.selectedBucketId)
    }

    @Test
    fun `a partial order's own bucket wins over the label's`() = runTest {
        val viewModel = createViewModel()

        scanRxLabel(viewModel, RxScanOutcome.Resume(txn(bucketId = "B9"), drug(), label(bucket = "B1")))

        assertEquals("B9", viewModel.uiState.value.selectedBucketId)
    }

    @Test
    fun `a second Rx does not carry the first one's drug details into the sheet`() = runTest {
        val viewModel = createViewModel()
        scanRxLabel(
            viewModel,
            RxScanOutcome.Resume(txn(txnId = 1L), drug(drugImagePath = "/data/drug1.webp", strength = "50 mg"), label()),
        )
        viewModel.onAction(DispenseFlowAction.RxCancelled)

        // The second Rx's txn has no drug, so the sheet must show blanks.
        scanRxLabel(
            viewModel,
            RxScanOutcome.Resume(txn(txnId = 2L, drugId = null, rxNo = "RX888"), null, label(rxNo = "RX888", bucket = null)),
        )

        val state = viewModel.uiState.value
        assertTrue(state.showRxDetails)
        assertEquals("RX888", state.rxNo)
        assertEquals("", state.drugName)
        assertEquals("", state.ndc)
        assertEquals("", state.drugImage)
        assertEquals("", state.selectedBucketId)
        assertNull(state.ndcStrength)
        assertNull(state.hl7ExpectedNdc)
    }

    @Test
    fun `a standalone label shows the sheet and writes nothing`() = runTest {
        val viewModel = createViewModel()
        val draft = StandaloneRxDraft(drugId = 55L, rxNo = "RX999", refillNo = null, bucket = "B1", targetCount = 10)

        scanRxLabel(viewModel, RxScanOutcome.Standalone(draft, drug(drugId = 55L)))

        val state = viewModel.uiState.value
        assertTrue(state.showRxDetails)
        assertEquals(draft, state.pendingStandaloneRx)
        assertNull(state.pendingRxResumeStage)
        assertEquals(0L, state.txnId)
        assertEquals(DispenseStage.PRE_RX, state.stage)
        assertEquals("Aspirin", state.drugName)
        assertEquals("NDC123", state.hl7ExpectedNdc)
        assertEquals("10", state.qty)
        assertEquals("B1", state.selectedBucketId)
        coVerify(exactly = 0) { dispenseFlowRepository.createStandaloneTxn(any(), any()) }
        coVerify(exactly = 0) { dispenseFlowRepository.clearGlovesPresent(any()) }
    }

    @Test
    fun `a failed Rx lookup shows its message`() = runTest {
        coEvery { rxLabelResolver.resolve(any()) } throws RuntimeException("boom")
        val viewModel = createViewModel()
        val effects = collectEffects(viewModel)

        viewModel.onAction(DispenseFlowAction.RxLabelScanned(RAW_RX_LABEL))
        advanceUntilIdle()

        assertEquals(listOf(DispenseFlowEffect.GenericError), effects)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `a cancelled Rx lookup shows no error`() = runTest {
        coEvery { rxLabelResolver.resolve(any()) } throws CancellationException()
        val viewModel = createViewModel()
        val effects = collectEffects(viewModel)

        viewModel.onAction(DispenseFlowAction.RxLabelScanned(RAW_RX_LABEL))
        advanceUntilIdle()

        assertTrue(effects.isEmpty())
    }

    @Test
    fun `the tour's Rx Next toasts when the demo drug can't be read back`() = runTest {
        coEvery { dispenseFlowRepository.upsertDemoDrug(any()) } returns null
        val viewModel = createViewModel()
        val effects = collectEffects(viewModel)
        FtueController.start(listOf(TourSection.DISPENSE), hl7Enabled = false, notesEnabled = false, bucketsEnabled = true)

        try {
            viewModel.onAction(DispenseFlowAction.TourRxNextTapped("Demo drug"))
            advanceUntilIdle()
        } finally {
            FtueController.end() // process-wide singleton: don't leak the tour into other tests
        }

        assertEquals(
            listOf(DispenseFlowEffect.ScanRejected(R.string.rx_label_drug_not_found_toast, pauseScanning = true)),
            effects,
        )
        assertFalse(viewModel.uiState.value.isLoading)
    }

    // ───────────────────────────── Proceed / Cancel ─────────────────────────────

    @Test
    fun `Proceed with nothing staged does nothing`() = runTest {
        val viewModel = createViewModel()

        viewModel.onAction(DispenseFlowAction.RxProceedTapped)
        advanceUntilIdle()

        assertEquals(DispenseStage.PRE_RX, viewModel.uiState.value.stage)
        coVerify(exactly = 0) { dispenseFlowRepository.createStandaloneTxn(any(), any()) }
    }

    @Test
    fun `Proceed on a partial order moves to the container step without a new txn`() = runTest {
        val viewModel = createViewModel()
        scanRxLabel(viewModel, RxScanOutcome.Resume(txn(txnId = 1L), drug(), label()))

        // Tapped twice: the second tap finds nothing left to do.
        viewModel.onAction(DispenseFlowAction.RxProceedTapped)
        viewModel.onAction(DispenseFlowAction.RxProceedTapped)
        advanceUntilIdle()

        assertEquals(DispenseStage.PRE_NDC, viewModel.uiState.value.stage)
        assertEquals(1L, viewModel.uiState.value.txnId)
        assertFalse(viewModel.uiState.value.showRxDetails)
        coVerify(exactly = 0) { dispenseFlowRepository.createStandaloneTxn(any(), any()) }
    }

    @Test
    fun `Proceed on a standalone label creates exactly one txn and moves to the container step`() = runTest {
        val viewModel = createViewModel()
        val draft = StandaloneRxDraft(drugId = 55L, rxNo = "RX999", refillNo = null, bucket = "B1", targetCount = 10)
        scanRxLabel(viewModel, RxScanOutcome.Standalone(draft, drug(drugId = 55L)))

        // PROCEED has no debounce, so two taps can land before the sheet goes.
        viewModel.onAction(DispenseFlowAction.RxProceedTapped)
        viewModel.onAction(DispenseFlowAction.RxProceedTapped)
        advanceUntilIdle()

        coVerify(exactly = 1) { dispenseFlowRepository.createStandaloneTxn(draft, 1L) }
        assertEquals(200L, viewModel.uiState.value.txnId)
        assertEquals(DispenseStage.PRE_NDC, viewModel.uiState.value.stage)
        assertFalse(viewModel.uiState.value.showRxDetails)
        assertNull(viewModel.uiState.value.pendingStandaloneRx)
    }

    @Test
    fun `a failed standalone save shows its message`() = runTest {
        coEvery { dispenseFlowRepository.createStandaloneTxn(any(), any()) } throws IllegalStateException("insert failed")
        val viewModel = createViewModel()
        val effects = collectEffects(viewModel)
        scanRxLabel(
            viewModel,
            RxScanOutcome.Standalone(StandaloneRxDraft(55L, "RX999", null, "B1", 10), drug(drugId = 55L)),
        )

        viewModel.onAction(DispenseFlowAction.RxProceedTapped)
        advanceUntilIdle()

        assertEquals(listOf(DispenseFlowEffect.GenericError), effects)
        assertEquals(DispenseStage.PRE_RX, viewModel.uiState.value.stage)
    }

    @Test
    fun `Cancel clears every field the Rx sheet renders`() = runTest {
        val viewModel = createViewModel()
        scanRxLabel(
            viewModel,
            RxScanOutcome.Resume(txn(), drug(isHazardous = true, drugImagePath = "/data/drug1.webp", strength = "50 mg"), label()),
        )

        viewModel.onAction(DispenseFlowAction.RxCancelled)

        val state = viewModel.uiState.value
        assertFalse(state.showRxDetails)
        assertNull(state.pendingRxResumeStage)
        assertNull(state.pendingStandaloneRx)
        assertEquals(0L, state.txnId)
        assertEquals("", state.drugName)
        assertEquals("", state.ndc)
        assertEquals("", state.drugImage)
        assertEquals("", state.selectedBucketId)
        assertNull(state.rxNo)
        assertNull(state.qty)
        assertNull(state.ndcStrength)
        assertNull(state.hl7ExpectedNdc)
        assertFalse(state.isHazardous)
    }

    @Test
    fun `Cancel after a standalone label leaves no txn behind`() = runTest {
        val viewModel = createViewModel()
        scanRxLabel(
            viewModel,
            RxScanOutcome.Standalone(StandaloneRxDraft(55L, "RX999", null, "B1", 10), drug(drugId = 55L)),
        )

        viewModel.onAction(DispenseFlowAction.RxCancelled)
        viewModel.onAction(DispenseFlowAction.RxProceedTapped)
        advanceUntilIdle()

        coVerify(exactly = 0) { dispenseFlowRepository.createStandaloneTxn(any(), any()) }
        assertEquals(0L, viewModel.uiState.value.txnId)
    }

    // ───────────────────────────── container ─────────────────────────────

    @Test
    fun `a container read on the Rx step is ignored`() = runTest {
        val viewModel = createViewModel()

        viewModel.onAction(DispenseFlowAction.ContainerScanned(GTIN, firstBottle))
        advanceUntilIdle()

        coVerify(exactly = 0) { containerVerifier.verify(any(), any()) }
    }

    @Test
    fun `a blank container read toasts and reads again at once`() = runTest {
        val viewModel = containerStepViewModel()
        val effects = collectEffects(viewModel)

        viewModel.onAction(DispenseFlowAction.ContainerScanned("  ", firstBottle))

        assertEquals(listOf(DispenseFlowEffect.ScanRejected(R.string.scan_correct_label, pauseScanning = false)), effects)
        coVerify(exactly = 0) { containerVerifier.verify(any(), any()) }
    }

    @Test
    fun `a second container read while one is loading is dropped`() = runTest {
        coEvery { containerVerifier.verify(any(), any()) } returns ContainerOutcome.NotFound
        val viewModel = containerStepViewModel()

        viewModel.onAction(DispenseFlowAction.ContainerScanned(GTIN, firstBottle))
        viewModel.onAction(DispenseFlowAction.ContainerScanned(GTIN, firstBottle))
        advanceUntilIdle()

        coVerify(exactly = 1) { containerVerifier.verify(any(), any()) }
    }

    @Test
    fun `the container is checked against the order's NDC`() = runTest {
        val viewModel = containerStepViewModel()

        scanContainer(viewModel, ContainerOutcome.NotFound)

        coVerify { containerVerifier.verify(GTIN, "NDC123") }
    }

    @Test
    fun `with no drug on the order the container is checked against nothing`() = runTest {
        val viewModel = containerStepViewModel(orderedDrug = null)

        scanContainer(viewModel, ContainerOutcome.NotFound)

        coVerify { containerVerifier.verify(GTIN, null) }
    }

    @Test
    fun `a saved match verifies the container with the first bottle and starts counting`() = runTest {
        val viewModel = containerStepViewModel()

        scanContainer(viewModel, ContainerOutcome.SavedMatch(drug(packageQty = 30)))

        val state = viewModel.uiState.value
        assertEquals(DispenseStage.COUNTING, state.stage)
        assertEquals("NDC123", state.ndcScannedValue)
        assertEquals("Aspirin", state.ndcDrugName)
        assertEquals(30, state.ndcPackageQty)
        assertNull(state.pendingFirstBottle)
        assertFalse(state.isLoading)
        coVerify { dispenseFlowRepository.markContainerVerified(7L, false, null, firstBottle) }
    }

    @Test
    fun `a server match takes the server's strength, form and image, then starts counting`() = runTest {
        val viewModel = containerStepViewModel()

        scanContainer(
            viewModel,
            ContainerOutcome.ServerMatch(drug(strength = "50 mg", dosageForm = "TABLET"), SERVER_IMAGE_URL),
        )

        val state = viewModel.uiState.value
        assertEquals(DispenseStage.COUNTING, state.stage)
        assertEquals("50 mg", state.ndcStrength)
        assertEquals("TABLET", state.ndcDosageForm)
        assertEquals(SERVER_IMAGE_URL, state.drugImage)
    }

    @Test
    fun `a server match with no image keeps the current one`() = runTest {
        val viewModel = containerStepViewModel(orderedDrug = drug(drugImagePath = "/images/aspirin.webp"))

        scanContainer(viewModel, ContainerOutcome.ServerMatch(drug(), imageUrl = null))

        assertEquals("/images/aspirin.webp", viewModel.uiState.value.drugImage)
    }

    @Test
    fun `a substitute asks first and verifies nothing yet`() = runTest {
        val viewModel = containerStepViewModel()

        scanContainer(viewModel, ContainerOutcome.Substitute(drug(ndc = "SUBNDC", drugName = "Generic"), SERVER_IMAGE_URL))

        val state = viewModel.uiState.value
        assertTrue(state.showNdcEquivalenceDialog)
        assertEquals("SUBNDC", state.ndcScannedValue)
        assertEquals("Generic", state.ndcDrugName)
        assertEquals(SERVER_IMAGE_URL, state.drugImage)
        assertEquals(DispenseStage.PRE_NDC, state.stage)
        coVerify(exactly = 0) { dispenseFlowRepository.markContainerVerified(any(), any(), any(), any()) }
    }

    @Test
    fun `a mismatch toasts, pauses scanning and stays on the container step`() = runTest {
        val viewModel = containerStepViewModel()
        val effects = collectEffects(viewModel)

        scanContainer(viewModel, ContainerOutcome.Mismatch(drug(ndc = "DIFFERENT")))

        assertEquals(listOf(DispenseFlowEffect.ScanRejected(R.string.rescan_ndc_does_not_match_toast, pauseScanning = true)), effects)
        assertEquals(DispenseStage.PRE_NDC, viewModel.uiState.value.stage)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `an unknown container toasts and reads again at once`() = runTest {
        val viewModel = containerStepViewModel()
        val effects = collectEffects(viewModel)

        scanContainer(viewModel, ContainerOutcome.NotFound)

        assertEquals(listOf(DispenseFlowEffect.ScanRejected(R.string.the_scanned_ndc_does_not_match, pauseScanning = false)), effects)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `a failed container lookup shows its message`() = runTest {
        coEvery { containerVerifier.verify(any(), any()) } throws RuntimeException("ndcfail")
        val viewModel = containerStepViewModel()
        val effects = collectEffects(viewModel)

        viewModel.onAction(DispenseFlowAction.ContainerScanned(GTIN, firstBottle))
        advanceUntilIdle()

        assertEquals(listOf(DispenseFlowEffect.GenericError), effects)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `a container lookup failure shows its message without logging it again`() = runTest {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.e(any(), any(), any()) } returns 0
        val viewModel = containerStepViewModel()
        val effects = collectEffects(viewModel)

        scanContainer(viewModel, ContainerOutcome.LookupFailed)

        assertEquals(listOf(DispenseFlowEffect.GenericError), effects)
        assertFalse(viewModel.uiState.value.isLoading)
        // DrugResolver logs the failure; the view model must not log it again.
        verify(exactly = 0) { android.util.Log.e(any(), "NDC barcode processing failed", any()) }
    }

    @Test
    fun `a txn gone by the time of the check stays on the container step`() = runTest {
        coEvery { dispenseFlowRepository.markContainerVerified(any(), any(), any(), any()) } returns false
        val viewModel = containerStepViewModel()

        scanContainer(viewModel, ContainerOutcome.SavedMatch(drug()))

        assertEquals(DispenseStage.PRE_NDC, viewModel.uiState.value.stage)
    }

    @Test
    fun `with no txn nothing is verified`() = runTest {
        // A reset on a fresh view model lands on the container step with no txn.
        val viewModel = createViewModel()
        viewModel.onAction(DispenseFlowAction.ResetConfirmed { true })
        advanceUntilIdle()

        scanContainer(viewModel, ContainerOutcome.SavedMatch(drug()))

        coVerify(exactly = 0) { dispenseFlowRepository.markContainerVerified(any(), any(), any(), any()) }
        assertEquals(DispenseStage.PRE_NDC, viewModel.uiState.value.stage)
    }

    @Test
    fun `an Rx label on the container step toasts and pauses scanning`() = runTest {
        val viewModel = containerStepViewModel()
        val effects = collectEffects(viewModel)

        viewModel.onAction(DispenseFlowAction.RxLabelScannedOnContainerStep)

        assertEquals(listOf(DispenseFlowEffect.ScanRejected(R.string.scan_ndc_toast, pauseScanning = true)), effects)
    }

    @Test
    fun `an Rx label flagged for the container step elsewhere does nothing`() = runTest {
        val viewModel = createViewModel()
        val effects = collectEffects(viewModel)

        viewModel.onAction(DispenseFlowAction.RxLabelScannedOnContainerStep)

        assertTrue(effects.isEmpty())
    }

    // ───────────────────────────── substitute ─────────────────────────────

    @Test
    fun `confirming a substitute saves it with the txn and starts counting`() = runTest {
        val viewModel = containerStepViewModel()
        scanContainer(
            viewModel,
            ContainerOutcome.Substitute(
                drug(ndc = "SUBNDC", drugName = "Generic", drugType = "X", packageQty = 7, strength = "10 mg", dosageForm = "TABLET"),
                SERVER_IMAGE_URL,
            ),
        )

        viewModel.onAction(DispenseFlowAction.SubstituteConfirmed)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isSubstituteConfirmed)
        assertFalse(viewModel.uiState.value.showNdcEquivalenceDialog)
        assertEquals(DispenseStage.COUNTING, viewModel.uiState.value.stage)
        coVerify {
            dispenseFlowRepository.markContainerVerified(
                7L,
                true,
                match { substituteDrug ->
                    substituteDrug.ndc == "SUBNDC" && substituteDrug.drugName == "Generic" &&
                        substituteDrug.drugType == "X" && substituteDrug.packageQty == 7 &&
                        substituteDrug.strength == "10 mg" && substituteDrug.dosageForm == "TABLET" &&
                        substituteDrug.drugImagePath == null
                },
                firstBottle,
            )
        }
    }

    @Test
    fun `a nameless substitute is saved with no name, not the ordered drug's`() = runTest {
        val viewModel = containerStepViewModel()
        scanContainer(viewModel, ContainerOutcome.Substitute(drug(ndc = "SUBNDC", drugName = null), SERVER_IMAGE_URL))

        viewModel.onAction(DispenseFlowAction.SubstituteConfirmed)
        advanceUntilIdle()

        assertEquals(DispenseStage.COUNTING, viewModel.uiState.value.stage)
        assertEquals("", viewModel.uiState.value.ndcDrugName)
        coVerify {
            dispenseFlowRepository.markContainerVerified(
                7L,
                true,
                match { substituteDrug -> substituteDrug.ndc == "SUBNDC" && substituteDrug.drugName == null },
                firstBottle,
            )
        }
    }

    @Test
    fun `a substitute with no scanned NDC is flagged but saves no drug`() = runTest {
        val viewModel = containerStepViewModel()

        viewModel.onAction(DispenseFlowAction.SubstituteConfirmed)
        advanceUntilIdle()

        coVerify { dispenseFlowRepository.markContainerVerified(7L, true, null, null) }
    }

    @Test
    fun `dismissing a substitute clears what the scan filled in`() = runTest {
        val viewModel = containerStepViewModel()
        scanContainer(viewModel, ContainerOutcome.Substitute(drug(ndc = "SUBNDC", drugType = "X"), SERVER_IMAGE_URL))

        viewModel.onAction(DispenseFlowAction.SubstituteDismissed)

        val state = viewModel.uiState.value
        assertFalse(state.showNdcEquivalenceDialog)
        assertEquals("", state.ndcScannedValue)
        assertEquals("", state.ndcDrugName)
        assertNull(state.ndcPackageQty)
        assertNull(state.ndcDrugType)
        assertEquals("", state.drugImage)
    }

    // ───────────────────────────── vial label ─────────────────────────────

    @Test
    fun `a blank vial read is read again at once`() = runTest {
        val viewModel = countingViewModel()
        val effects = collectEffects(viewModel)

        viewModel.onAction(DispenseFlowAction.VialLabelScanned("   "))

        assertEquals(listOf(DispenseFlowEffect.ScanRejected(null, pauseScanning = false)), effects)
    }

    @Test
    fun `a vial read with no Rx on the txn is read again at once`() = runTest {
        val viewModel = createViewModel()
        val effects = collectEffects(viewModel)

        viewModel.onAction(DispenseFlowAction.VialLabelScanned("RX999"))

        assertEquals(listOf(DispenseFlowEffect.ScanRejected(null, pauseScanning = false)), effects)
    }

    @Test
    fun `a vial Rx label with the txn's Rx matches`() = runTest {
        every { rxLabelResolver.rxNoOf(RAW_RX_LABEL) } returns "RX999"
        val viewModel = countingViewModel()
        val effects = collectEffects(viewModel)

        viewModel.onAction(DispenseFlowAction.VialLabelScanned(RAW_RX_LABEL))

        assertEquals(listOf(DispenseFlowEffect.VialLabelMatched), effects)
    }

    @Test
    fun `a bare Rx number matches in any case`() = runTest {
        val viewModel = countingViewModel()
        val effects = collectEffects(viewModel)

        viewModel.onAction(DispenseFlowAction.VialLabelScanned("rx999"))

        assertEquals(listOf(DispenseFlowEffect.VialLabelMatched), effects)
    }

    @Test
    fun `a wrong vial toasts once per cooldown`() = runTest {
        val viewModel = countingViewModel()
        val effects = collectEffects(viewModel)

        viewModel.onAction(DispenseFlowAction.VialLabelScanned("WRONGRX"))
        // Immediate repeat, within the cooldown: no second toast.
        viewModel.onAction(DispenseFlowAction.VialLabelScanned("WRONGRX"))

        assertEquals(
            listOf(
                DispenseFlowEffect.ScanRejected(R.string.vial_rx_mismatch_toast, pauseScanning = true),
                DispenseFlowEffect.ScanRejected(null, pauseScanning = false),
            ),
            effects,
        )
    }

    @Test
    fun `a vial label that parses to no Rx is read again at once`() = runTest {
        every { rxLabelResolver.rxNoOf("garbage|data") } returns null
        val viewModel = countingViewModel()
        val effects = collectEffects(viewModel)

        viewModel.onAction(DispenseFlowAction.VialLabelScanned("garbage|data"))

        assertEquals(listOf(DispenseFlowEffect.ScanRejected(null, pauseScanning = false)), effects)
    }

    // ───────────────────────────── queue ─────────────────────────────

    @Test
    fun `the queue shows the repository's rows`() = runTest {
        val queueItem = QueueItem.Dispense(
            txn = DispenseTxnWithDrug(
                txnId = 1L, drugName = "D", ndc = "N", drugType = "CII", bucketId = "b",
                createdAt = 1L, targetCount = 5, bottlesJson = null, totalPillCount = 0,
                isComingFromHL7 = false, isNdcVerified = false,
                priority = TxnPriority.High, isHazardous = true,
            ),
            isHazardous = true,
            isHighPriority = true,
            isControlled = true,
        )
        every { dispenseFlowRepository.observeQueue(1L) } returns flowOf(listOf(queueItem))

        val viewModel = createViewModel(fromQueue = true)
        advanceUntilIdle()

        assertEquals(listOf(queueItem), viewModel.uiState.value.queueItems)
    }

    @Test
    fun `a queue tab is selected and a null tab is ignored`() = runTest {
        val viewModel = createViewModel()

        viewModel.onAction(DispenseFlowAction.QueueFilterSelected(KpiFilter.DISP_HAZARDOUS))
        viewModel.onAction(DispenseFlowAction.QueueFilterSelected(null))

        assertEquals(KpiFilter.DISP_HAZARDOUS, viewModel.uiState.value.selectedQueueFilter)
    }

    @Test
    fun `tapping a missing queue row does nothing`() = runTest {
        val viewModel = createViewModel(fromQueue = true)

        viewModel.onAction(DispenseFlowAction.QueueItemTapped(5L))
        advanceUntilIdle()

        assertEquals(DispenseStage.QUEUE, viewModel.uiState.value.stage)
        assertEquals(0L, viewModel.uiState.value.txnId)
    }

    @Test
    fun `tapping a verified queue row goes to counting`() = runTest {
        coEvery { dispenseFlowRepository.loadTxn(5L) } returns txn(txnId = 5L, isNdcVerified = true)
        val viewModel = createViewModel(fromQueue = true)

        viewModel.onAction(DispenseFlowAction.QueueItemTapped(5L))
        advanceUntilIdle()

        assertEquals(DispenseStage.COUNTING, viewModel.uiState.value.stage)
        assertEquals(5L, viewModel.uiState.value.txnId)
        coVerify { dispenseFlowRepository.clearGlovesPresent(5L) }
    }

    @Test
    fun `tapping an unverified queue row goes to the container step`() = runTest {
        coEvery { dispenseFlowRepository.loadTxn(5L) } returns txn(txnId = 5L, isNdcVerified = false)
        val viewModel = createViewModel(fromQueue = true)

        viewModel.onAction(DispenseFlowAction.QueueItemTapped(5L))
        advanceUntilIdle()

        assertEquals(DispenseStage.PRE_NDC, viewModel.uiState.value.stage)
    }

    @Test
    fun `leaving for the queue resets the flow but keeps where it was opened from`() = runTest {
        val viewModel = createViewModel(fromQueue = true)
        scanRxLabel(viewModel, RxScanOutcome.Resume(txn(), drug(), label()))

        viewModel.onAction(DispenseFlowAction.LeavingForQueue)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(DispenseStage.QUEUE, state.stage)
        assertTrue(state.isFromQueue)
        assertFalse(state.showRxDetails)
        assertEquals(0L, state.txnId)
        assertEquals(KpiFilter.DISP_PENDING, state.selectedQueueFilter)
    }

    @Test
    fun `a finished count returns to the queue when dispenses are pending`() = runTest {
        coEvery { dispenseFlowRepository.hasPendingDispenses(1L) } returns true
        val viewModel = countingViewModel()

        viewModel.onAction(DispenseFlowAction.CountFinished)
        advanceUntilIdle()

        assertEquals(DispenseStage.QUEUE, viewModel.uiState.value.stage)
    }

    @Test
    fun `a finished count with nothing pending leaves for the Dashboard`() = runTest {
        val viewModel = countingViewModel()
        val effects = collectEffects(viewModel)

        viewModel.onAction(DispenseFlowAction.CountFinished)
        advanceUntilIdle()

        assertEquals(listOf(DispenseFlowEffect.LeaveToDashboard), effects)
    }

    // ───────────────────────────── reset ─────────────────────────────

    @Test
    fun `a reset goes back to the container step and clears the container scan`() = runTest {
        val viewModel = containerStepViewModel()
        scanContainer(viewModel, ContainerOutcome.SavedMatch(drug()))

        viewModel.onAction(DispenseFlowAction.ResetConfirmed { true })
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(DispenseStage.PRE_NDC, state.stage)
        assertEquals("", state.ndcScannedValue)
        assertEquals("", state.ndcDrugName)
        assertNull(state.pendingFirstBottle)
        assertFalse(state.isSubstituteConfirmed)
    }

    @Test
    fun `a reset takes the drug row's image, strength and form back`() = runTest {
        // The container scan leaves the server's image URL in state; the details
        // bar loads a local file, so reset has to take the drug row's path back.
        val viewModel = countingViewModel()
        coEvery { dispenseFlowRepository.loadOrderedDrug(7L) } returns
            drug(drugImagePath = "/local/drug.webp", strength = "20mg", dosageForm = "CAPSULE")

        viewModel.onAction(DispenseFlowAction.ResetConfirmed { true })
        advanceUntilIdle()

        assertEquals("/local/drug.webp", viewModel.uiState.value.drugImage)
        assertEquals("20mg", viewModel.uiState.value.ndcStrength)
        assertEquals("CAPSULE", viewModel.uiState.value.ndcDosageForm)
    }

    @Test
    fun `a container scan after a reset from a resumed count checks the order's NDC`() = runTest {
        val viewModel = countingViewModel()
        viewModel.onAction(DispenseFlowAction.ResetConfirmed { true })
        advanceUntilIdle()

        scanContainer(viewModel, ContainerOutcome.NotFound)

        coVerify { containerVerifier.verify(GTIN, "NDC123") }
    }

    @Test
    fun `a reset runs the count reset before flipping the stage`() = runTest {
        // The count delete has to finish before the stage moves, or the SCAN strip
        // composes against dying counts.
        val viewModel = createViewModel()
        var stageDuringCountReset: DispenseStage? = null

        viewModel.onAction(DispenseFlowAction.ResetConfirmed { stageDuringCountReset = viewModel.uiState.value.stage; true })
        advanceUntilIdle()

        assertEquals(DispenseStage.PRE_RX, stageDuringCountReset)
        assertEquals(DispenseStage.PRE_NDC, viewModel.uiState.value.stage)
    }

    @Test
    fun `a failed count reset keeps the stage`() = runTest {
        val viewModel = countingViewModel()

        viewModel.onAction(DispenseFlowAction.ResetConfirmed { false })
        advanceUntilIdle()

        assertEquals(DispenseStage.COUNTING, viewModel.uiState.value.stage)
    }

    @Test
    fun `a reset still moves to the container step when the drug lookup throws`() = runTest {
        // The count is already wiped by then, so a failed display lookup must not strand the stage.
        val viewModel = countingViewModel()
        coEvery { dispenseFlowRepository.loadOrderedDrug(any()) } throws RuntimeException("db down")

        viewModel.onAction(DispenseFlowAction.ResetConfirmed { true })
        advanceUntilIdle()

        assertEquals(DispenseStage.PRE_NDC, viewModel.uiState.value.stage)
    }

    @Test
    fun `a reset shows the generic error when the count reset throws`() = runTest {
        val viewModel = countingViewModel()
        val effects = collectEffects(viewModel)

        viewModel.onAction(DispenseFlowAction.ResetConfirmed { throw RuntimeException("reset failed") })
        advanceUntilIdle()

        assertEquals(listOf(DispenseFlowEffect.GenericError), effects)
    }
}
