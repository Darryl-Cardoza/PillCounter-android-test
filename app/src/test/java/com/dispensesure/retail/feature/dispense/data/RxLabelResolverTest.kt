package com.dispensesure.retail.feature.dispense.data

import com.dispensesure.retail.core.room.models.DispenseTxnEntity
import com.dispensesure.retail.core.room.models.DrugMasterEntity
import com.dispensesure.retail.core.room.models.enums.CountStatus
import com.dispensesure.retail.core.scanning.data.DrugResolver
import com.dispensesure.retail.core.scanning.domain.model.DrugLookup
import com.dispensesure.retail.core.utils.common.ParsedScanData
import com.dispensesure.retail.core.utils.common.parseScanData
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import com.dispensesure.retail.feature.dispense.domain.model.StandaloneRxDraft
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

private const val RAW_RX_LABEL = "RX999|NDC123|10|B1"

/** Unit tests for [RxLabelResolver]. */
class RxLabelResolverTest {

    private val preferenceHelper: PreferenceHelper = mockk(relaxed = true)
    private val dispenseFlowRepository: DispenseFlowRepository = mockk()
    private val drugResolver: DrugResolver = mockk()
    private val resolver = RxLabelResolver(preferenceHelper, dispenseFlowRepository, drugResolver)

    @Before
    fun setUp() {
        // Mock the top-level parseScanData function via its generated file-class.
        mockkStatic("com.dispensesure.retail.core.utils.common.ParsedScanDataKt")
        every { preferenceHelper.getBarcodeRegex() } returns "{RXNO}|{NDCNO}|{QTY}|{BUCKET}"
        every { parseScanData(any(), any()) } returns label()
        coEvery { dispenseFlowRepository.loadMostRecentTxnByRxNo(any()) } returns null
        coEvery { dispenseFlowRepository.loadTxnByRxNoAndFill(any(), any()) } returns null
        coEvery { dispenseFlowRepository.loadActiveTxnByRxNo(any()) } returns null
        coEvery { dispenseFlowRepository.loadDrug(any()) } returns null
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun label(
        rxNo: String? = "RX999",
        refillNo: String? = null,
        ndcNo: String? = "NDC123",
        qty: String? = "10",
        bucket: String? = "B1",
    ) = ParsedScanData(rxNo = rxNo, refillNo = refillNo, ndcNo = ndcNo, qty = qty, bucket = bucket)

    private fun txn(
        drugId: Long? = 10L,
        status: CountStatus = CountStatus.PARTIAL,
        isNdcVerified: Boolean? = false,
    ) = DispenseTxnEntity(
        txnId = 1L, drugId = drugId, status = status, isNdcVerified = isNdcVerified,
        rxNo = "RX999", targetCount = 10,
    )

    private fun drug(drugId: Long = 10L, ndc: String = "NDC123") =
        DrugMasterEntity(drugId = drugId, drugName = "Aspirin", ndc = ndc)

    private fun orderOnFile(existingTxn: DispenseTxnEntity, orderedDrug: DrugMasterEntity?) {
        coEvery { dispenseFlowRepository.loadActiveTxnByRxNo("RX999") } returns existingTxn
        coEvery { dispenseFlowRepository.loadDrug(10L) } returns orderedDrug
    }

    // ─────────────────────────── label format ───────────────────────────

    @Test
    fun `a blank read is invalid`() = runTest {
        assertEquals(RxScanOutcome.Invalid, resolver.resolve("   "))
    }

    @Test
    fun `no configured label format is invalid`() = runTest {
        every { preferenceHelper.getBarcodeRegex() } returns ""

        assertEquals(RxScanOutcome.Invalid, resolver.resolve(RAW_RX_LABEL))
    }

    @Test
    fun `a label missing a field is invalid`() = runTest {
        every { parseScanData(any(), any()) } returns label(ndcNo = null)

        assertEquals(RxScanOutcome.Invalid, resolver.resolve(RAW_RX_LABEL))
    }

    @Test
    fun `a non-numeric qty is invalid and looks nothing up`() = runTest {
        every { parseScanData(any(), any()) } returns label(qty = "not-a-number")

        assertEquals(RxScanOutcome.Invalid, resolver.resolve(RAW_RX_LABEL))
        coVerify(exactly = 0) { dispenseFlowRepository.loadActiveTxnByRxNo(any()) }
    }

    // ─────────────────────────── already dispensed ───────────────────────────

    @Test
    fun `an Rx whose latest txn is completed is blocked`() = runTest {
        coEvery { dispenseFlowRepository.loadMostRecentTxnByRxNo("RX999") } returns txn(status = CountStatus.COMPLETED)

        assertEquals(RxScanOutcome.AlreadyCompleted, resolver.resolve(RAW_RX_LABEL))
        coVerify(exactly = 0) { dispenseFlowRepository.loadActiveTxnByRxNo(any()) }
    }

    @Test
    fun `an Rx whose latest txn is force completed is blocked`() = runTest {
        coEvery { dispenseFlowRepository.loadMostRecentTxnByRxNo("RX999") } returns txn(status = CountStatus.FORCE_COMPLETED)

        assertEquals(RxScanOutcome.AlreadyCompleted, resolver.resolve(RAW_RX_LABEL))
    }

    @Test
    fun `an Rx whose latest txn is partial is not blocked`() = runTest {
        val existingTxn = txn()
        val orderedDrug = drug()
        coEvery { dispenseFlowRepository.loadMostRecentTxnByRxNo("RX999") } returns existingTxn
        orderOnFile(existingTxn, orderedDrug)

        assertEquals(RxScanOutcome.Resume(existingTxn, orderedDrug, label()), resolver.resolve(RAW_RX_LABEL))
    }

    @Test
    fun `a label with a refill number checks that fill, not the whole Rx`() = runTest {
        // Same rxNo, but refill 0 already went out — scanning refill 1's label must NOT
        // be blocked by refill 0's COMPLETED status. getByRxNoAndFillNo keys on both.
        every { parseScanData(any(), any()) } returns label(refillNo = "1")

        assertEquals(RxScanOutcome.NotFound, resolver.resolve(RAW_RX_LABEL))
        coVerify { dispenseFlowRepository.loadTxnByRxNoAndFill("RX999", "1") }
        coVerify(exactly = 0) { dispenseFlowRepository.loadMostRecentTxnByRxNo(any()) }
    }

    @Test
    fun `a label with a refill number is blocked when that fill is completed`() = runTest {
        every { parseScanData(any(), any()) } returns label(refillNo = "0")
        coEvery { dispenseFlowRepository.loadTxnByRxNoAndFill("RX999", "0") } returns txn(status = CountStatus.COMPLETED)

        assertEquals(RxScanOutcome.AlreadyCompleted, resolver.resolve(RAW_RX_LABEL))
    }

    // ─────────────────────────── no order ───────────────────────────

    @Test
    fun `no order and not standalone is not found`() = runTest {
        // isStandaloneMode() defaults to false on the relaxed mock.
        assertEquals(RxScanOutcome.NotFound, resolver.resolve(RAW_RX_LABEL))
        coVerify(exactly = 0) { drugResolver.resolve(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `no order in standalone mode stages the label with its drug`() = runTest {
        every { preferenceHelper.isStandaloneMode() } returns true
        val standaloneDrug = drug(drugId = 55L)
        coEvery {
            drugResolver.resolve("NDC123", allowNoName = true)
        } returns DrugLookup.Found(standaloneDrug, fromServer = false)

        assertEquals(
            RxScanOutcome.Standalone(
                draft = StandaloneRxDraft(drugId = 55L, rxNo = "RX999", refillNo = null, bucket = "B1", targetCount = 10),
                drug = standaloneDrug,
            ),
            resolver.resolve(RAW_RX_LABEL),
        )
    }

    @Test
    fun `no order in standalone mode with an unknown drug is drug not found`() = runTest {
        every { preferenceHelper.isStandaloneMode() } returns true
        coEvery {
            drugResolver.resolve("NDC123", allowNoName = true)
        } returns DrugLookup.NotFound

        assertEquals(RxScanOutcome.DrugNotFound, resolver.resolve(RAW_RX_LABEL))
    }

    @Test
    fun `a standalone label saves a nameless server drug as the unknown drug`() = runTest {
        every { preferenceHelper.isStandaloneMode() } returns true
        coEvery { drugResolver.resolve(any(), any(), any(), any(), any()) } returns DrugLookup.Found(drug(), fromServer = true)

        resolver.resolve(RAW_RX_LABEL)

        coVerify { drugResolver.resolve("NDC123", allowNoName = true) }
    }

    // ─────────────────────────── order on file ───────────────────────────

    @Test
    fun `a partial order resumes with its drug and the scanned label`() = runTest {
        val existingTxn = txn()
        val orderedDrug = drug()
        orderOnFile(existingTxn, orderedDrug)

        assertEquals(RxScanOutcome.Resume(existingTxn, orderedDrug, label()), resolver.resolve(RAW_RX_LABEL))
    }

    @Test
    fun `a partial order whose drug differs from the label is a mismatch`() = runTest {
        orderOnFile(txn(isNdcVerified = false), drug(ndc = "99999-111-22"))

        assertEquals(RxScanOutcome.NdcMismatch, resolver.resolve(RAW_RX_LABEL))
    }

    @Test
    fun `a verified partial order still needs a matching label`() = runTest {
        // The container is already verified, but the label still has to match.
        orderOnFile(txn(isNdcVerified = true), drug(ndc = "99999-111-22"))

        assertEquals(RxScanOutcome.NdcMismatch, resolver.resolve(RAW_RX_LABEL))
    }

    @Test
    fun `a dashed label NDC matches an undashed order NDC`() = runTest {
        // Labels print dashed NDCs, so formatting alone must not reject a match.
        every { parseScanData(any(), any()) } returns label(ndcNo = "12345-678-90")
        val existingTxn = txn()
        val undashedNdcDrug = drug(ndc = "1234567890")
        orderOnFile(existingTxn, undashedNdcDrug)

        assertEquals(
            RxScanOutcome.Resume(existingTxn, undashedNdcDrug, label(ndcNo = "12345-678-90")),
            resolver.resolve(RAW_RX_LABEL),
        )
    }

    @Test
    fun `an order with no drug skips the NDC check`() = runTest {
        // PMS can create an order with no drug. Nothing to compare, so no block.
        val existingTxn = txn(drugId = null)
        coEvery { dispenseFlowRepository.loadActiveTxnByRxNo("RX999") } returns existingTxn

        assertEquals(RxScanOutcome.Resume(existingTxn, null, label()), resolver.resolve(RAW_RX_LABEL))
    }

    @Test
    fun `an order on hold blocks the scan`() = runTest {
        orderOnFile(txn(status = CountStatus.ON_HOLD), drug())

        assertEquals(RxScanOutcome.OnHold, resolver.resolve(RAW_RX_LABEL))
    }

    @Test
    fun `an order on hold with the wrong drug is a mismatch first`() = runTest {
        // A wrong-drug label must not resume someone else's held txn.
        orderOnFile(txn(status = CountStatus.ON_HOLD), drug(ndc = "99999-111-22"))

        assertEquals(RxScanOutcome.NdcMismatch, resolver.resolve(RAW_RX_LABEL))
    }

    @Test
    fun `an active order in any other status is not found`() = runTest {
        orderOnFile(txn(status = CountStatus.COMPLETED), drug())

        assertEquals(RxScanOutcome.NotFound, resolver.resolve(RAW_RX_LABEL))
    }

    // ─────────────────────────── vial Rx ───────────────────────────

    @Test
    fun `rxNoOf gives the label's Rx, trimmed`() {
        every { parseScanData(any(), RAW_RX_LABEL) } returns label(rxNo = " RX999 ")

        assertEquals("RX999", resolver.rxNoOf(RAW_RX_LABEL))
    }

    @Test
    fun `rxNoOf is null with no label format`() {
        every { preferenceHelper.getBarcodeRegex() } returns ""

        assertNull(resolver.rxNoOf(RAW_RX_LABEL))
    }

    @Test
    fun `rxNoOf is null when the label has no Rx`() {
        every { parseScanData(any(), any()) } returns label(rxNo = null)

        assertNull(resolver.rxNoOf(RAW_RX_LABEL))
    }
}
