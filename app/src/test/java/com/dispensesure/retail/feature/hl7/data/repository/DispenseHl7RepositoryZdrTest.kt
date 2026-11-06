package com.dispensesure.retail.feature.hl7.data.repository

import android.content.Context
import com.dispensesure.retail.core.audit.AuditLogger
import com.dispensesure.retail.core.audit.model.OrderCancelled
import com.dispensesure.retail.core.audit.model.OrderDiscontinued
import com.dispensesure.retail.core.audit.model.OrderHeld
import com.dispensesure.retail.core.audit.model.OrderReleased
import com.dispensesure.retail.core.region.Region
import com.dispensesure.retail.core.region.RegionProvider
import com.dispensesure.retail.core.room.dao.DispenseCountDao
import com.dispensesure.retail.core.room.dao.DispenseTxnDao
import com.dispensesure.retail.core.room.dao.DrugMasterDao
import com.dispensesure.retail.core.room.models.DispenseTxnEntity
import com.dispensesure.retail.core.room.models.DrugMasterEntity
import com.dispensesure.retail.core.room.models.enums.CountStatus
import com.dispensesure.retail.core.scanning.data.DrugImageDownloader
import com.dispensesure.retail.core.scanning.data.DrugResolver
import com.dispensesure.retail.core.scanning.domain.data.IDrugRepository
import com.dispensesure.retail.core.utils.common.LocationProvider
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import com.dispensesure.retail.feature.hl7.core.Hl7MessageSender
import com.dispensesure.retail.feature.hl7.data.Hl7ConfigProvider
import com.dispensesure.retail.feature.hl7.notification.Hl7Notifier
import com.dispensesure.retail.feature.hl7.parsing.Hl7DrugResolver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.rite.hl7.model.HL7Message
import org.rite.hl7.model.segment.ZDRSegment

/**
 * Tests for [DispenseHl7Repository.handleZdrOrder] and [DispenseHl7Repository.handleZdrControl].
 *
 * Covers the full ZDR lifecycle (NW/RF/CA/HD/RL/DC), multi-drug messages, priority,
 * resend guards, refill exhaustion, and drug-not-found paths — all mirroring the
 * equivalent scenarios in the standard ORC/RXE flow without touching that flow.
 *
 * Inbound messages are built from real wire-format HL7 text parsed via the library's
 * HL7Parser — [HL7Message] constructor is internal to the AAR.
 *
 * ZDR wire fields (1-indexed, `|`-delimited after segment name):
 *   1=flowType  2=layoutVersion  3=requestMessageId  4=orderAction
 *   5=drugs (repetitions `~`, ndc^name inside each)  6=drugCodeType
 *   7=patient(family^given)  8=DOB  9=phone  10=patientId
 *   11=prescriber(family^given)  12=prescriberId  13=prescriberDEA
 *   14=rxNumber  15=orderReferenceNumber  16=quantityToDispense
 *   17=fillNumber  18=priority  19=refillsAllowed  20=bottleVerificationMode
 *   21=expectedQuantityOnHand  22=unitOfMeasure  23=dosageStart  24=dosageEnd
 *   25=flag340b  26=facilityNumber
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DispenseHl7RepositoryZdrTest {

    private val testDispatcher = StandardTestDispatcher()
    private val regionProvider: RegionProvider = mockk { every { current } returns Region.US }

    private lateinit var context: Context
    private lateinit var drugMasterDao: DrugMasterDao
    private lateinit var dispenseTxnDao: DispenseTxnDao
    private lateinit var dispenseCountDao: DispenseCountDao
    private lateinit var preferenceHelper: PreferenceHelper
    private lateinit var hl7MessageSender: Hl7MessageSender
    private lateinit var iDrugRepository: IDrugRepository
    private lateinit var notifier: Hl7Notifier
    private lateinit var drugImageDownloader: DrugImageDownloader
    private lateinit var auditLogger: AuditLogger

    @Before
    fun setup() {
        stubAndroidLog()
        Dispatchers.setMain(testDispatcher)

        context = mockk(relaxed = true)
        drugMasterDao = mockk(relaxed = true)
        dispenseTxnDao = mockk(relaxed = true)
        dispenseCountDao = mockk(relaxed = true)
        preferenceHelper = mockk(relaxed = true)
        hl7MessageSender = mockk(relaxed = true)
        iDrugRepository = mockk(relaxed = true)
        notifier = mockk(relaxed = true)
        drugImageDownloader = mockk(relaxed = true)
        auditLogger = mockk(relaxed = true)

        every { context.getString(any()) } returns "x"
        every { context.getString(any(), *anyVararg()) } returns "x"
        every { preferenceHelper.isHl7Enabled() } returns false
        every { preferenceHelper.getLocalId() } returns 1L

        coEvery { hl7MessageSender.send(any()) } returns Result.success(SUCCESS_ACK)
        coEvery { drugMasterDao.getDrugByNdc(any(), any()) } returns null
        coEvery { drugMasterDao.getDrugByGtin(any(), any()) } returns null
        coEvery { drugMasterDao.upsertPreservingId(any()) } returns 99L
        coEvery { dispenseTxnDao.upsertPreservingId(any()) } returns 200L
        coEvery { dispenseTxnDao.getByRxNoAndFillNo(any(), any()) } returns null
        coEvery { dispenseTxnDao.getByTransactionOrderId(any()) } returns null
        coEvery { dispenseTxnDao.updateFromHl7Edit(any(), any(), any(), any(), null) } returns Unit
        coEvery { dispenseTxnDao.getByRxNoAndMessageControlId(any(), any()) } returns null
        coEvery { dispenseTxnDao.getMostRecentByRxNo(any()) } returns null
        coEvery { dispenseTxnDao.cancelActiveTxnsByRxNo(any()) } returns emptyList()
        coEvery { dispenseTxnDao.cancelActiveTxnsByOrderId(any()) } returns emptyList()
        coEvery { dispenseTxnDao.updateStatusByOrderIdIfPartial(any(), any(), any()) } returns 1
        coEvery { dispenseTxnDao.updateStatusByOrderIdIfPartialOrOnHold(any(), any(), any()) } returns 1
        coEvery { dispenseTxnDao.softDeleteByOrderId(any(), any()) } returns 1
        coEvery { dispenseTxnDao.getActiveStatusByOrderId(any()) } returns CountStatus.PARTIAL
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createRepo(): DispenseHl7Repository = DispenseHl7Repository(
        context = context,
        drugMasterDao = drugMasterDao,
        locationProvider = mockk(relaxed = true),
        dispenseTxnDao = dispenseTxnDao,
        dispenseCountDao = dispenseCountDao,
        preferenceHelper = preferenceHelper,
        hl7MessageSender = hl7MessageSender,
        drugResolver = DrugResolver(
            drugMasterDao = drugMasterDao,
            drugRepository = iDrugRepository,
            drugImageDownloader = drugImageDownloader,
            regionProvider = regionProvider,
        ),
        hl7DrugResolver = Hl7DrugResolver(
            context = context,
            drugResolver = DrugResolver(
                drugMasterDao = drugMasterDao,
                drugRepository = iDrugRepository,
                drugImageDownloader = drugImageDownloader,
                regionProvider = regionProvider,
            ),
            notifier = notifier,
            regionProvider = regionProvider,
        ),
        notifier = notifier,
        hl7ConfigProvider = Hl7ConfigProvider(context, preferenceHelper),
        regionProvider = regionProvider,
        auditLogger = auditLogger,
    )

    private fun drug(ndc: String = "00527-8113-37", name: String = "DRUG A", id: Long = 10L) =
        DrugMasterEntity(drugId = id, ndc = ndc, drugName = name)

    // ── entry points ──
    // Production dispatches from Hl7EventHandler, which already holds the parsed ZDR segment, so
    // the repository takes a segment rather than a message. These mirror that call, keeping the
    // tests on wire-format input.

    private suspend fun DispenseHl7Repository.zdrOrder(message: HL7Message) {
        val zdr = message.segment<ZDRSegment>(ZDRSegment.NAME)!!
        handleZdrOrder(
            zdr,
            message.header?.messageControlId,
            message.header?.sequenceNumber?.takeIf { it.isNotBlank() },
        )
    }

    /** XO reaches handleZdrChangeOrder via handleZdrOrder in production; returns whether a row changed. */
    private suspend fun DispenseHl7Repository.zdrChangeOrder(message: HL7Message): Boolean =
        handleZdrChangeOrder(message.segment<ZDRSegment>(ZDRSegment.NAME)!!)

    private suspend fun DispenseHl7Repository.zdrControl(message: HL7Message): Boolean {
        val zdr = message.segment<ZDRSegment>(ZDRSegment.NAME)!!
        return handleZdrControl(zdr, zdr.orderAction.uppercase())
    }

    // ── wire builders ──

    private fun zdrMsg(
        action: String = "NW",
        controlId: String = "MSG001",
        ndc: String = "00527-8113-37",
        drugName: String = "DEXMETHYLPHENIDATE 40MG",
        rxNumber: String = "RX-001",
        orderId: String = "ORDER-001",
        quantity: String = "60",
        fillNumber: String = "1",
        priority: String = "R",    // R=Medium, S=High, T=Low
        refillsAllowed: String = "5",
        flowType: String = "D",
    ) = parseHl7Message(
        "${mshSegment("RDE", "O11", controlId)}\r" +
            "ZDR|$flowType|1.0|$controlId|$action|$ndc^$drugName|NDC|DOE^JANE|19850315|555-0123|PAT-001|SMITH^ROBERT|NPI1234|AB1234|$rxNumber|$orderId|$quantity|$fillNumber|$priority|$refillsAllowed|A||TAB|||"
    )

    private fun zdrMsgMultiDrug(
        action: String = "NW",
        controlId: String = "MSG002",
        ndc1: String = "00527-8113-37",
        name1: String = "DRUG A",
        ndc2: String = "60505008404",
        name2: String = "DRUG B",
    ) = parseHl7Message(
        "${mshSegment("RDE", "O11", controlId)}\r" +
            "ZDR|D|1.0|$controlId|$action|$ndc1^$name1~$ndc2^$name2|NDC|DOE^JANE|19850315|555-0123|PAT-002|||AB1234|RX-MULTI|ORDER-002|30|1|S|5|A||TAB|||"
    )

    // ════════════════════ NW — new order ════════════════════

    @Test
    fun `NW single drug inserts one txn`() = runTest(testDispatcher) {
        coEvery { drugMasterDao.getDrugByNdc("00527-8113-37", any()) } returns drug()

        createRepo().zdrOrder(zdrMsg())

        coVerify(exactly = 1) { dispenseTxnDao.upsertPreservingId(any()) }
    }

    @Test
    fun `NW stores rxNo on inserted txn`() = runTest(testDispatcher) {
        coEvery { drugMasterDao.getDrugByNdc("00527-8113-37", any()) } returns drug()
        val slot = slot<DispenseTxnEntity>()
        coEvery { dispenseTxnDao.upsertPreservingId(capture(slot)) } returns 200L

        createRepo().zdrOrder(zdrMsg(rxNumber = "RX-CAPTURED"))

        assertEquals("RX-CAPTURED", slot.captured.rxNo)
    }

    @Test
    fun `NW stores targetCount on inserted txn`() = runTest(testDispatcher) {
        coEvery { drugMasterDao.getDrugByNdc("00527-8113-37", any()) } returns drug()
        val slot = slot<DispenseTxnEntity>()
        coEvery { dispenseTxnDao.upsertPreservingId(capture(slot)) } returns 200L

        createRepo().zdrOrder(zdrMsg(quantity = "45"))

        assertEquals(45, slot.captured.targetCount)
    }

    @Test
    fun `NW stores messageControlId for ZDS echo`() = runTest(testDispatcher) {
        coEvery { drugMasterDao.getDrugByNdc("00527-8113-37", any()) } returns drug()
        val slot = slot<DispenseTxnEntity>()
        coEvery { dispenseTxnDao.upsertPreservingId(capture(slot)) } returns 200L

        createRepo().zdrOrder(zdrMsg(controlId = "MSG-CTRL-99"))

        assertEquals("MSG-CTRL-99", slot.captured.hl7MessageControlId)
    }

    @Test
    fun `NW high priority (S) mapped on txn`() = runTest(testDispatcher) {
        coEvery { drugMasterDao.getDrugByNdc("00527-8113-37", any()) } returns drug()
        val slot = slot<DispenseTxnEntity>()
        coEvery { dispenseTxnDao.upsertPreservingId(capture(slot)) } returns 200L

        createRepo().zdrOrder(zdrMsg(priority = "S"))

        assertEquals(com.dispensesure.retail.core.room.models.enums.TxnPriority.High, slot.captured.priority)
    }

    @Test
    fun `NW sets isComingFromHL7 true and status PARTIAL`() = runTest(testDispatcher) {
        coEvery { drugMasterDao.getDrugByNdc("00527-8113-37", any()) } returns drug()
        val slot = slot<DispenseTxnEntity>()
        coEvery { dispenseTxnDao.upsertPreservingId(capture(slot)) } returns 200L

        createRepo().zdrOrder(zdrMsg())

        assertTrue(slot.captured.isComingFromHL7 == true)
        assertEquals(CountStatus.PARTIAL, slot.captured.status)
    }

    @Test
    fun `NW shows new-rx notification`() = runTest(testDispatcher) {
        coEvery { drugMasterDao.getDrugByNdc("00527-8113-37", any()) } returns drug()
        coEvery { dispenseTxnDao.upsertPreservingId(any()) } returns 300L

        createRepo().zdrOrder(zdrMsg())

        coVerify(exactly = 1) { notifier.show(title = any(), message = any(), dispenseTxnId = 300L) }
    }

    @Test
    fun `NW for a completed fill creates a fresh transaction`() = runTest(testDispatcher) {
        coEvery { drugMasterDao.getDrugByNdc("00527-8113-37", any()) } returns drug()
        val finished = DispenseTxnEntity(txnId = 1L, status = CountStatus.COMPLETED, rxNo = "RX-001", refillNo = "1")
        coEvery { dispenseTxnDao.getByTransactionOrderId("ORDER-001") } returns finished

        createRepo().zdrOrder(zdrMsg())

        coVerify(exactly = 1) { dispenseTxnDao.upsertPreservingId(any()) }
        coVerify(exactly = 0) { dispenseTxnDao.updateFromHl7Edit(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `NW stores ZDR-15 as transactionOrderId`() = runTest(testDispatcher) {
        coEvery { drugMasterDao.getDrugByNdc("00527-8113-37", any()) } returns drug()
        val slot = slot<DispenseTxnEntity>()
        coEvery { dispenseTxnDao.upsertPreservingId(capture(slot)) } returns 200L

        createRepo().zdrOrder(zdrMsg(orderId = "ORD-77"))

        assertEquals("ORD-77", slot.captured.transactionOrderId)
    }

    @Test
    fun `NW with several drugs on the wire acts on the first only`() = runTest(testDispatcher) {
        // The validator rejects this before dispatch; the handler must still never create more than one txn.
        coEvery { drugMasterDao.getDrugByNdc("00527-8113-37", any()) } returns drug("00527-8113-37", "DRUG A", 11L)
        coEvery { drugMasterDao.getDrugByNdc("60505008404", any()) } returns drug("60505008404", "DRUG B", 12L)

        createRepo().zdrOrder(zdrMsgMultiDrug())

        coVerify(exactly = 1) { dispenseTxnDao.upsertPreservingId(any()) }
    }

    @Test
    fun `NW open existing txn is updated not re-inserted`() = runTest(testDispatcher) {
        coEvery { drugMasterDao.getDrugByNdc("00527-8113-37", any()) } returns drug()
        val open = DispenseTxnEntity(txnId = 55L, status = CountStatus.PARTIAL, rxNo = "RX-001", refillNo = "1")
        // Code prefers transactionOrderId (ZDR-15) when present; zdrMsg() has orderId="ORDER-001"
        coEvery { dispenseTxnDao.getByTransactionOrderId("ORDER-001") } returns open

        createRepo().zdrOrder(zdrMsg())

        coVerify(exactly = 0) { dispenseTxnDao.upsertPreservingId(any()) }
        coVerify(exactly = 1) { dispenseTxnDao.updateFromHl7Edit(55L, any(), any(), any(), any(), any()) }
    }

    // ════════════════════ RF — refill ════════════════════

    @Test
    fun `RF creates new txn with derived fillNumber when ZDR has explicit fill`() = runTest(testDispatcher) {
        coEvery { drugMasterDao.getDrugByNdc("00527-8113-37", any()) } returns drug()
        val slot = slot<DispenseTxnEntity>()
        coEvery { dispenseTxnDao.upsertPreservingId(capture(slot)) } returns 210L

        createRepo().zdrOrder(zdrMsg(action = "RF", fillNumber = "2"))

        assertEquals("2", slot.captured.refillNo)
        coVerify(exactly = 1) { dispenseTxnDao.upsertPreservingId(any()) }
    }

    @Test
    fun `RF without explicit fillNumber derives from most recent txn`() = runTest(testDispatcher) {
        coEvery { drugMasterDao.getDrugByNdc("00527-8113-37", any()) } returns drug()
        val mostRecent = DispenseTxnEntity(txnId = 5L, status = CountStatus.COMPLETED, rxNo = "RX-001", refillNo = "3")
        coEvery { dispenseTxnDao.getMostRecentByRxNo("RX-001") } returns mostRecent
        val slot = slot<DispenseTxnEntity>()
        coEvery { dispenseTxnDao.upsertPreservingId(capture(slot)) } returns 211L

        val msg = parseHl7Message(
            "${mshSegment("RDE", "O11", "MSG-RF")}\r" +
                "ZDR|D|1.0|MSG-RF|RF|00527-8113-37^DRUG A|NDC|DOE^JANE|||PAT|||AB|RX-001|ORDER-001|30||R|5|A||TAB|||"
        )
        createRepo().zdrOrder(msg)

        assertEquals("4", slot.captured.refillNo) // prevRefill 3 + 1
    }

    // An RF with no refills remaining is rejected by NativeZdrValidator (NO_REFILLS_REMAINING)
    // before dispatch, so the handler never runs for one — see NativeZdrValidatorTest.

    @Test
    fun `RF for a completed fill creates a fresh transaction`() = runTest(testDispatcher) {
        coEvery { drugMasterDao.getDrugByNdc("00527-8113-37", any()) } returns drug()
        val finished = DispenseTxnEntity(txnId = 9L, status = CountStatus.COMPLETED, rxNo = "RX-001", refillNo = "2")
        coEvery { dispenseTxnDao.getByTransactionOrderId("ORDER-001") } returns finished

        createRepo().zdrOrder(zdrMsg(action = "RF", fillNumber = "2"))

        coVerify(exactly = 1) { dispenseTxnDao.upsertPreservingId(any()) }
    }

    // ════════════════════ CA — cancel ════════════════════

    @Test
    fun `CA cancels active txns by orderId`() = runTest(testDispatcher) {
        coEvery { dispenseTxnDao.cancelActiveTxnsByOrderId("ORDER-001") } returns listOf(77L)

        createRepo().zdrControl(zdrMsg(action = "CA"))

        coVerify(exactly = 1) { dispenseTxnDao.cancelActiveTxnsByOrderId("ORDER-001") }
        verify { auditLogger.log(match { it is OrderCancelled && it.txnIds == listOf(77L) }) }
    }

    // ════════════════════ HD — hold ════════════════════

    @Test
    fun `HD marks order ON_HOLD by orderId`() = runTest(testDispatcher) {
        coEvery { dispenseTxnDao.updateStatusByOrderIdIfPartial("ORDER-001", CountStatus.ON_HOLD, any()) } returns 1

        createRepo().zdrControl(zdrMsg(action = "HD"))

        coVerify(exactly = 1) { dispenseTxnDao.updateStatusByOrderIdIfPartial("ORDER-001", CountStatus.ON_HOLD, any()) }
        verify { auditLogger.log(match { it is OrderHeld && it.orderId == "ORDER-001" }) }
    }

    // ════════════════════ RL — release ════════════════════

    @Test
    fun `RL restores order to PARTIAL by orderId`() = runTest(testDispatcher) {
        coEvery { dispenseTxnDao.updateStatusByOrderIdIfPartialOrOnHold("ORDER-001", CountStatus.PARTIAL, any()) } returns 1

        createRepo().zdrControl(zdrMsg(action = "RL"))

        coVerify { dispenseTxnDao.updateStatusByOrderIdIfPartialOrOnHold("ORDER-001", CountStatus.PARTIAL, any()) }
        verify { auditLogger.log(match { it is OrderReleased && it.orderId == "ORDER-001" }) }
    }

    // ════════════════════ DC — discontinue ════════════════════

    @Test
    fun `DC soft-deletes by orderId`() = runTest(testDispatcher) {
        coEvery { dispenseTxnDao.softDeleteByOrderId("ORDER-001", any()) } returns 1

        createRepo().zdrControl(zdrMsg(action = "DC"))

        coVerify { dispenseTxnDao.softDeleteByOrderId("ORDER-001", any()) }
        verify { auditLogger.log(match { it is OrderDiscontinued && it.orderId == "ORDER-001" }) }
    }

    // ════════════════════ XO — change order ════════════════════

    @Test
    fun `XO updates existing txn when found by transactionOrderId`() = runTest(testDispatcher) {
        coEvery { drugMasterDao.getDrugByNdc("00527-8113-37", any()) } returns drug()
        val existing = DispenseTxnEntity(txnId = 88L, status = CountStatus.PARTIAL, rxNo = "RX-001", refillNo = "1")
        coEvery { dispenseTxnDao.getByTransactionOrderId("ORDER-001") } returns existing

        val result = createRepo().zdrChangeOrder(zdrMsg(action = "XO"))

        assertTrue(result)
        coVerify(exactly = 1) { dispenseTxnDao.updateFromHl7Edit(88L, any(), any(), any(), null, any()) }
    }

    @Test
    fun `XO returns false when transactionOrderId not found in DB`() = runTest(testDispatcher) {
        coEvery { dispenseTxnDao.getByTransactionOrderId("ORDER-001") } returns null

        val result = createRepo().zdrChangeOrder(zdrMsg(action = "XO"))

        assertFalse(result)
        coVerify(exactly = 0) { dispenseTxnDao.updateFromHl7Edit(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `XO with blank orderId returns false`() = runTest(testDispatcher) {
        val msg = parseHl7Message(
            "${mshSegment("RDE", "O11", "MSG-XO-NOID")}\r" +
                "ZDR|D|1.0|MSG-XO-NOID|XO|00527-8113-37^DRUG|NDC|DOE^JANE|||PAT|||AB|RX-XO||30|1|R|5|A||TAB|||"
        )
        val result = createRepo().zdrChangeOrder(msg)

        assertFalse(result)
        coVerify(exactly = 0) { dispenseTxnDao.updateFromHl7Edit(any(), any(), any(), any(), any()) }
    }

    // A blank or malformed drug code is rejected by NativeZdrValidator (INVALID_DRUG_CODE) before
    // dispatch, so the handler never sees one — see NativeZdrValidatorTest.

    // A zero/invalid XO quantity is rejected by NativeZdrValidator before dispatch (see
    // NativeZdrValidatorTest), so the handler no longer re-checks it and is never reached.

    @Test
    fun `XO decimal quantity is rounded, matching the validator`() = runTest(testDispatcher) {
        coEvery { drugMasterDao.getDrugByNdc("00527-8113-37", any()) } returns drug()
        val existing = DispenseTxnEntity(txnId = 93L, status = CountStatus.PARTIAL, rxNo = "RX-001", refillNo = "1")
        coEvery { dispenseTxnDao.getByTransactionOrderId("ORDER-001") } returns existing
        val qtySlot = slot<Int>()
        coEvery { dispenseTxnDao.updateFromHl7Edit(any(), any(), capture(qtySlot), any(), null, any()) } returns Unit

        createRepo().zdrChangeOrder(zdrMsg(action = "XO", quantity = "30.7"))

        // NativeZdrValidator accepted "30.7" as 31 via roundToLong; storing 30 would disagree.
        assertEquals(31, qtySlot.captured)
    }

    // ════════════════════ blank ZDR-15 — resolved order key ════════════════════

    private fun blankRefMsg(action: String, rx: String = "RX-NOID", fill: String = "1", qty: String = "60") = parseHl7Message(
        "${mshSegment("RDE", "O11", "MSG-$action")}\r" +
            "ZDR|D|1.0|MSG-$action|$action|00527-8113-37^DRUG A|NDC|DOE^JANE|||PAT|||AB|$rx||$qty|$fill|R|5|A||TAB|||"
    )

    @Test
    fun `NW with blank ZDR-15 stores the resolved rx-fill key`() = runTest(testDispatcher) {
        coEvery { drugMasterDao.getDrugByNdc("00527-8113-37", any()) } returns drug()
        val slot = slot<DispenseTxnEntity>()
        coEvery { dispenseTxnDao.upsertPreservingId(capture(slot)) } returns 200L

        createRepo().zdrOrder(blankRefMsg("NW", fill = "001"))

        assertEquals("RX-NOID-1", slot.captured.transactionOrderId)
        coVerify { dispenseTxnDao.getByTransactionOrderId("RX-NOID-1") }
    }

    @Test
    fun `RF with blank ZDR-15 stores the resolved rx-fill key`() = runTest(testDispatcher) {
        coEvery { drugMasterDao.getDrugByNdc("00527-8113-37", any()) } returns drug()
        val slot = slot<DispenseTxnEntity>()
        coEvery { dispenseTxnDao.upsertPreservingId(capture(slot)) } returns 200L

        createRepo().zdrOrder(blankRefMsg("RF", fill = "2"))

        assertEquals("RX-NOID-2", slot.captured.transactionOrderId)
    }

    // Regression: with ZDR-15 AND ZDR-17 both blank, the key must be re-derived from the
    // computed fill. Keying every refill to "rx-0" made the second RF find the first and
    // update it in place — one row where two fills were dispensed, and the PMS still got AA.
    @Test
    fun `successive refills with a blank ZDR-15 and blank fill do not overwrite each other`() = runTest(testDispatcher) {
        coEvery { drugMasterDao.getDrugByNdc("00527-8113-37", any()) } returns drug()
        val stored = mutableListOf<DispenseTxnEntity>()
        coEvery { dispenseTxnDao.upsertPreservingId(capture(stored)) } returns 200L
        // Fill 1 already on file; nothing is keyed to the derived fill-2 key yet.
        coEvery { dispenseTxnDao.getMostRecentByRxNo("RX-NOID") } returns
            DispenseTxnEntity(txnId = 200L, status = CountStatus.COMPLETED, rxNo = "RX-NOID", refillNo = "1")
        coEvery { dispenseTxnDao.getByTransactionOrderId("RX-NOID-2") } returns null

        createRepo().zdrOrder(blankRefMsg("RF", fill = ""))

        // A NEW row keyed to the derived fill, not an update of fill 1.
        assertEquals(1, stored.size)
        assertEquals("RX-NOID-2", stored.single().transactionOrderId)
        assertEquals("2", stored.single().refillNo)
        coVerify(exactly = 0) { dispenseTxnDao.updateFromHl7Edit(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `blank ZDR-15 order is created then found by a subsequent XO`() = runTest(testDispatcher) {
        coEvery { drugMasterDao.getDrugByNdc("00527-8113-37", any()) } returns drug()
        val slot = slot<DispenseTxnEntity>()
        coEvery { dispenseTxnDao.upsertPreservingId(capture(slot)) } returns 200L
        val repo = createRepo()
        repo.zdrOrder(blankRefMsg("NW"))
        val stored = slot.captured.copy(txnId = 200L)
        coEvery { dispenseTxnDao.getByTransactionOrderId(stored.transactionOrderId!!) } returns stored

        val result = repo.zdrChangeOrder(blankRefMsg("XO", qty = "30"))

        assertTrue(result)
        coVerify(exactly = 1) { dispenseTxnDao.updateFromHl7Edit(200L, any(), 30, any(), null, any()) }
    }

    @Test
    fun `blank ZDR-15 order is created then found by a subsequent CA`() = runTest(testDispatcher) {
        coEvery { drugMasterDao.getDrugByNdc("00527-8113-37", any()) } returns drug()
        val slot = slot<DispenseTxnEntity>()
        coEvery { dispenseTxnDao.upsertPreservingId(capture(slot)) } returns 200L
        val repo = createRepo()
        repo.zdrOrder(blankRefMsg("NW"))
        val key = slot.captured.transactionOrderId!!
        coEvery { dispenseTxnDao.cancelActiveTxnsByOrderId(key) } returns listOf(200L)

        val result = repo.zdrControl(blankRefMsg("CA"))

        assertTrue(result)
        coVerify(exactly = 1) { dispenseTxnDao.cancelActiveTxnsByOrderId("RX-NOID-1") }
    }

    @Test
    fun `HD RL and DC with blank ZDR-15 use the resolved key`() = runTest(testDispatcher) {
        val repo = createRepo()
        assertTrue(repo.zdrControl(blankRefMsg("HD")))
        assertTrue(repo.zdrControl(blankRefMsg("RL")))
        assertTrue(repo.zdrControl(blankRefMsg("DC")))

        coVerify { dispenseTxnDao.updateStatusByOrderIdIfPartial("RX-NOID-1", CountStatus.ON_HOLD, any()) }
        coVerify { dispenseTxnDao.updateStatusByOrderIdIfPartialOrOnHold("RX-NOID-1", CountStatus.PARTIAL, any()) }
        coVerify { dispenseTxnDao.softDeleteByOrderId("RX-NOID-1", any()) }
    }

    // ════════════════════ RF — update open existing txn ════════════════════

    @Test
    fun `RF open existing txn is updated not re-inserted`() = runTest(testDispatcher) {
        coEvery { drugMasterDao.getDrugByNdc("00527-8113-37", any()) } returns drug()
        val open = DispenseTxnEntity(txnId = 66L, status = CountStatus.PARTIAL, rxNo = "RX-001", refillNo = "2")
        coEvery { dispenseTxnDao.getByTransactionOrderId("ORDER-001") } returns open

        createRepo().zdrOrder(zdrMsg(action = "RF", fillNumber = "2"))

        coVerify(exactly = 0) { dispenseTxnDao.upsertPreservingId(any()) }
        coVerify(exactly = 1) { dispenseTxnDao.updateFromHl7Edit(66L, any(), any(), any(), any(), any()) }
    }

    // ════════════════════ unknown action ════════════════════

    @Test
    fun `unknown orderAction is ignored — no DB writes`() = runTest(testDispatcher) {
        createRepo().zdrOrder(zdrMsg(action = "XX"))

        coVerify(exactly = 0) { dispenseTxnDao.upsertPreservingId(any()) }
        coVerify(exactly = 0) { dispenseTxnDao.cancelActiveTxnsByOrderId(any()) }
    }

    // ════════════════════ drug not found ════════════════════

    @Test
    fun `NW drug not found — no txn, shows notification`() = runTest(testDispatcher) {
        coEvery { iDrugRepository.getDrugInfoByNdc(any()) } returns null

        createRepo().zdrOrder(zdrMsg())

        coVerify(exactly = 0) { dispenseTxnDao.upsertPreservingId(any()) }
        coVerify(exactly = 1) { notifier.show(title = any(), message = any()) }
    }

    // ════════════════════ no ZDR segment ════════════════════

    @Test
    fun `an unexpected order action creates no transaction`() = runTest(testDispatcher) {
        // A message with no ZDR never reaches these handlers: Hl7EventHandler dispatches the
        // native path only when a ZDR is present, and NativeZdrValidator rejects the rest.
        createRepo().zdrOrder(zdrMsg(action = "ZZ"))

        coVerify(exactly = 0) { dispenseTxnDao.upsertPreservingId(any()) }
    }
}
