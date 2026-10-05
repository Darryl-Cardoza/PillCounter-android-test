package com.dispensesure.retail.feature.hl7.presentation

import android.content.Context
import com.dispensesure.retail.core.room.dao.DrugMasterDao
import com.dispensesure.retail.core.room.dao.PillCountTxnDao
import com.dispensesure.retail.core.room.models.DrugMasterEntity
import com.dispensesure.retail.core.room.models.PillCountTxnEntity
import com.dispensesure.retail.core.room.models.enums.CountStatus
import com.dispensesure.retail.core.room.models.enums.TxnPriority
import com.dispensesure.retail.core.scanning.data.DrugImageDownloader
import com.dispensesure.retail.core.scanning.data.DrugRepository
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import com.dispensesure.retail.feature.hl7.data.repository.Hl7Repository
import com.dispensesure.retail.feature.hl7.notification.Hl7Notifier
import com.dispensesure.retail.feature.hl7.parsing.model.Hl7OrderAction
import com.dispensesure.retail.feature.hl7.parsing.model.OrderGroup
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.rite.hl7.parser.HL7ParseResult
import org.rite.hl7.parser.HL7Parser

/**
 * Each test joins the Job from process(), so checks run after the handler has finished.
 * HL7Message comes from parsing wire text; its constructor is internal to the HL7 library AAR.
 */
class Hl7OrderProcessorTest {

    private val hl7Parser = HL7Parser.Builder().build()
    private val message = parse("MSH|^~\\&|PMS|FAC|APP|STORE|20240101||RDE^O11|$MSG_ID|P|2.5\r")

    private val context: Context = mockk(relaxed = true)
    private val pillCountTxnDao: PillCountTxnDao = mockk(relaxed = true)
    private val drugMasterDao: DrugMasterDao = mockk(relaxed = true)
    private val preferenceHelper: PreferenceHelper = mockk(relaxed = true)
    private val drugRepository: DrugRepository = mockk(relaxed = true)
    private val drugImageDownloader: DrugImageDownloader = mockk(relaxed = true)
    private val notifier: Hl7Notifier = mockk(relaxed = true)
    private val hl7Repository: Hl7Repository = mockk(relaxed = true)

    private lateinit var processor: Hl7OrderProcessor

    @Before
    fun setup() {
        every { context.getString(any()) } returns "x"
        every { context.getString(any(), *anyVararg()) } returns "x"
        every { preferenceHelper.getLocalId() } returns 1L
        coEvery { drugMasterDao.getDrugByNdc(NDC) } returns DrugMasterEntity(ndc = NDC, drugName = DRUG_NAME)
        coEvery { drugMasterDao.upsertPreservingId(any()) } returns DRUG_ID
        coEvery { pillCountTxnDao.upsertPreservingId(any()) } returns NEW_TXN_ID
        // Relaxed lookups would return a fake row; default to "nothing saved yet".
        coEvery { pillCountTxnDao.getByRxNoAndFillNo(any(), any()) } returns null
        coEvery { pillCountTxnDao.getByRxNoAndMessageControlId(any(), any()) } returns null
        coEvery { pillCountTxnDao.getMostRecentByRxNo(any()) } returns null

        processor = Hl7OrderProcessor(
            context, pillCountTxnDao, drugMasterDao, preferenceHelper,
            drugRepository, drugImageDownloader, notifier, hl7Repository,
        )
    }

    // ───────────────────────── NW ─────────────────────────

    @Test
    fun `NW with no saved row inserts a new PARTIAL transaction`() = runTest {
        processor.process(newOrder(order("NW"))).join()

        val row = insertedRow()
        assertEquals(0L, row.txnId)
        assertEquals(CountStatus.PARTIAL, row.status)
        assertEquals(RX, row.rxNo)
        assertEquals(ORDER_ID, row.transactionOrderId)
        assertEquals("0", row.refillNo)
        assertEquals(30, row.targetCount)
        assertEquals(MSG_ID, row.hl7MessageControlId)
        coVerify { hl7Repository.insertZinContainerDetails(NEW_TXN_ID, message) }
    }

    @Test
    fun `NW with a saved PARTIAL row updates it in place`() = runTest {
        coEvery { pillCountTxnDao.getByRxNoAndFillNo(RX, "0") } returns savedRow(CountStatus.PARTIAL, "0")

        processor.process(newOrder(order("NW"))).join()

        verifyUpdatedInPlace()
    }

    @Test
    fun `NW with a saved ON_HOLD row updates it in place`() = runTest {
        coEvery { pillCountTxnDao.getByRxNoAndFillNo(RX, "0") } returns savedRow(CountStatus.ON_HOLD, "0")

        processor.process(newOrder(order("NW"))).join()

        verifyUpdatedInPlace()
    }

    @Test
    fun `NW with a saved COMPLETED row from another message inserts a new transaction`() = runTest {
        coEvery { pillCountTxnDao.getByRxNoAndFillNo(RX, "0") } returns savedRow(CountStatus.COMPLETED, "0")

        processor.process(newOrder(order("NW"))).join()

        verifyInsertedNew(refillNo = "0")
    }

    @Test
    fun `NW with a saved FORCE_COMPLETED row from another message inserts a new transaction`() = runTest {
        coEvery { pillCountTxnDao.getByRxNoAndFillNo(RX, "0") } returns savedRow(CountStatus.FORCE_COMPLETED, "0")

        processor.process(newOrder(order("NW"))).join()

        verifyInsertedNew(refillNo = "0")
    }

    @Test
    fun `NW resent after its COMPLETED row writes nothing`() = runTest {
        val finished = savedRow(CountStatus.COMPLETED, "0")
        coEvery { pillCountTxnDao.getByRxNoAndFillNo(RX, "0") } returns finished
        coEvery { pillCountTxnDao.getByRxNoAndMessageControlId(RX, MSG_ID) } returns finished

        processor.process(newOrder(order("NW"))).join()

        verifyIgnoredAsResend()
    }

    @Test
    fun `NW resent after its FORCE_COMPLETED row writes nothing`() = runTest {
        val finished = savedRow(CountStatus.FORCE_COMPLETED, "0")
        coEvery { pillCountTxnDao.getByRxNoAndFillNo(RX, "0") } returns finished
        coEvery { pillCountTxnDao.getByRxNoAndMessageControlId(RX, MSG_ID) } returns finished

        processor.process(newOrder(order("NW"))).join()

        verifyIgnoredAsResend()
    }

    @Test
    fun `NW with missing NDC notifies and writes nothing`() = runTest {
        processor.process(newOrder(order("NW", giveCode = null))).join()

        verify(exactly = 1) { notifier.show(any(), any()) }
        verifyNoTxnWrite()
    }

    @Test
    fun `NW with missing quantity notifies and writes nothing`() = runTest {
        processor.process(newOrder(order("NW", dispenseAmount = null))).join()

        verify(exactly = 1) { notifier.show(any(), any()) }
        verifyNoTxnWrite()
    }

    @Test
    fun `NW with zero quantity notifies and writes nothing`() = runTest {
        processor.process(newOrder(order("NW", dispenseAmount = 0))).join()

        verify(exactly = 1) { notifier.show(any(), any()) }
        verifyNoTxnWrite()
    }

    // ───────────────────────── RF ─────────────────────────

    @Test
    fun `RF with RXE-16 and no saved row inserts the computed refill`() = runTest {
        processor.process(refill(order("RF", pendingRefills = 1, refillNumber = 2))).join()

        assertEquals("2", insertedRow().refillNo)
    }

    @Test
    fun `RF with RXE-16 and a saved PARTIAL row updates it in place`() = runTest {
        coEvery { pillCountTxnDao.getByRxNoAndFillNo(RX, "2") } returns savedRow(CountStatus.PARTIAL, "2")

        processor.process(refill(order("RF", pendingRefills = 1, refillNumber = 2))).join()

        verifyUpdatedInPlace()
    }

    @Test
    fun `RF with RXE-16 and a saved COMPLETED row from another message inserts a new transaction`() = runTest {
        coEvery { pillCountTxnDao.getByRxNoAndFillNo(RX, "2") } returns savedRow(CountStatus.COMPLETED, "2")

        processor.process(refill(order("RF", pendingRefills = 1, refillNumber = 2))).join()

        verifyInsertedNew(refillNo = "2")
    }

    @Test
    fun `RF with RXE-16 resent after its COMPLETED row writes nothing`() = runTest {
        val finished = savedRow(CountStatus.COMPLETED, "2")
        coEvery { pillCountTxnDao.getByRxNoAndFillNo(RX, "2") } returns finished
        coEvery { pillCountTxnDao.getByRxNoAndMessageControlId(RX, MSG_ID) } returns finished

        processor.process(refill(order("RF", pendingRefills = 1, refillNumber = 2))).join()

        verifyIgnoredAsResend()
    }

    @Test
    fun `RF with no refills left notifies and writes nothing`() = runTest {
        processor.process(refill(order("RF", pendingRefills = 0, refillNumber = 3))).join()

        verify(exactly = 1) { notifier.show(any(), any()) }
        verifyNoTxnWrite()
    }

    @Test
    fun `RF without RXE-16 adds one to the latest refill`() = runTest {
        coEvery { pillCountTxnDao.getMostRecentByRxNo(RX) } returns savedRow(CountStatus.COMPLETED, "2")

        processor.process(refill(order("RF"))).join()

        assertEquals("3", insertedRow().refillNo)
    }

    @Test
    fun `RF without RXE-16 resent while its row is open updates that row`() = runTest {
        val resent = savedRow(CountStatus.PARTIAL, "3")
        coEvery { pillCountTxnDao.getByRxNoAndMessageControlId(RX, MSG_ID) } returns resent
        coEvery { pillCountTxnDao.getByRxNoAndFillNo(RX, "3") } returns resent

        processor.process(refill(order("RF"))).join()

        verifyUpdatedInPlace()
        coVerify(exactly = 0) { pillCountTxnDao.getMostRecentByRxNo(any()) }
    }

    @Test
    fun `RF without RXE-16 resent after its row finished writes nothing`() = runTest {
        coEvery { pillCountTxnDao.getByRxNoAndMessageControlId(RX, MSG_ID) } returns savedRow(CountStatus.COMPLETED, "3")

        processor.process(refill(order("RF"))).join()

        verifyNoTxnWrite()
        coVerify(exactly = 0) { pillCountTxnDao.getMostRecentByRxNo(any()) }
    }

    // ───────────────────────── HD / RL ─────────────────────────

    @Test
    fun `HD uses the PARTIAL-only status update`() = runTest {
        processor.process(Hl7OrderAction.Hold(ORDER_ID)).join()

        coVerify(exactly = 1) { pillCountTxnDao.updateStatusByOrderIdIfPartial(ORDER_ID, CountStatus.ON_HOLD, any()) }
    }

    @Test
    fun `RL uses the PARTIAL-or-ON_HOLD status update`() = runTest {
        processor.process(Hl7OrderAction.Release(ORDER_ID)).join()

        coVerify(exactly = 1) {
            pillCountTxnDao.updateStatusByOrderIdIfPartialOrOnHold(ORDER_ID, CountStatus.PARTIAL, any())
        }
    }

    // ───────────────────────── helpers ─────────────────────────

    private fun parse(raw: String) = when (val r = hl7Parser.parse(raw)) {
        is HL7ParseResult.Success -> r.message
        is HL7ParseResult.Failure -> r.partialMessage ?: error("parse failed: ${r.errors}")
    }

    private fun order(
        control: String,
        giveCode: String? = NDC,
        dispenseAmount: Int? = 30,
        pendingRefills: Int? = null,
        refillNumber: Int = 0,
    ) = OrderGroup(
        messageControlId = MSG_ID,
        sendingApplication = "PMS",
        sendingFacility = "FAC",
        receivingApplication = "APP",
        receivingFacility = "STORE",
        messageDateTime = "20240101",
        orderControl = control,
        placerOrderNumber = ORDER_ID,
        fillerOrderNumber = RX,
        orderStatus = null,
        priority = TxnPriority.Medium,
        giveCode = giveCode,
        giveName = DRUG_NAME,
        dispenseAmount = dispenseAmount,
        giveUnits = null,
        substitutionStatus = null,
        numberOfRefills = null,
        prescriptionNumber = RX,
        pendingRefills = pendingRefills,
        refillNumber = refillNumber,
    )

    private fun newOrder(order: OrderGroup) = Hl7OrderAction.NewOrder(order, message)
    private fun refill(order: OrderGroup) = Hl7OrderAction.Refill(order, message)

    private fun savedRow(status: CountStatus, refillNo: String) = PillCountTxnEntity(
        txnId = SAVED_TXN_ID, isDispense = true, status = status, rxNo = RX, refillNo = refillNo,
    )

    private fun insertedRow(): PillCountTxnEntity {
        val row = slot<PillCountTxnEntity>()
        coVerify(exactly = 1) { pillCountTxnDao.upsertPreservingId(capture(row)) }
        return row.captured
    }

    private fun verifyUpdatedInPlace() {
        coVerify(exactly = 1) {
            pillCountTxnDao.updateFromHl7Edit(SAVED_TXN_ID, DRUG_ID, 30, TxnPriority.Medium, null, any())
        }
        coVerify(exactly = 0) { pillCountTxnDao.upsertPreservingId(any()) }
    }

    private fun verifyInsertedNew(refillNo: String) {
        val row = insertedRow()
        assertEquals(0L, row.txnId)
        assertEquals(RX, row.rxNo)
        assertEquals(refillNo, row.refillNo)
        coVerify(exactly = 0) { pillCountTxnDao.updateFromHl7Edit(any(), any(), any(), any(), any(), any()) }
    }

    private fun verifyNoTxnWrite() {
        coVerify(exactly = 0) { pillCountTxnDao.upsertPreservingId(any()) }
        coVerify(exactly = 0) { pillCountTxnDao.updateFromHl7Edit(any(), any(), any(), any(), any(), any()) }
    }

    private fun verifyIgnoredAsResend() {
        verifyNoTxnWrite()
        coVerify(exactly = 0) { drugMasterDao.getDrugByNdc(any()) }
    }

    private companion object {
        const val MSG_ID = "MSG-55"
        const val RX = "RX-1001"
        const val ORDER_ID = "ORD-1"
        const val NDC = "12345678901"
        const val DRUG_NAME = "Drug"
        const val DRUG_ID = 5L
        const val SAVED_TXN_ID = 7L
        const val NEW_TXN_ID = 9L
    }
}
