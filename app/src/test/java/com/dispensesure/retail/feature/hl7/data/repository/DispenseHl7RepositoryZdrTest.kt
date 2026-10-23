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

/**
 * Tests for [DispenseHl7Repository.handleZdrNewOrRefill].
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
        notifier = notifier,
        hl7ConfigProvider = Hl7ConfigProvider(context, preferenceHelper),
        regionProvider = regionProvider,
        auditLogger = auditLogger,
    )

    private fun drug(ndc: String = "00527-8113-37", name: String = "DRUG A", id: Long = 10L) =
        DrugMasterEntity(drugId = id, ndc = ndc, drugName = name)

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

        createRepo().handleZdrNewOrRefill(zdrMsg())

        coVerify(exactly = 1) { dispenseTxnDao.upsertPreservingId(any()) }
    }

    @Test
    fun `NW stores rxNo on inserted txn`() = runTest(testDispatcher) {
        coEvery { drugMasterDao.getDrugByNdc("00527-8113-37", any()) } returns drug()
        val slot = slot<DispenseTxnEntity>()
        coEvery { dispenseTxnDao.upsertPreservingId(capture(slot)) } returns 200L

        createRepo().handleZdrNewOrRefill(zdrMsg(rxNumber = "RX-CAPTURED"))

        assertEquals("RX-CAPTURED", slot.captured.rxNo)
    }

    @Test
    fun `NW stores targetCount on inserted txn`() = runTest(testDispatcher) {
        coEvery { drugMasterDao.getDrugByNdc("00527-8113-37", any()) } returns drug()
        val slot = slot<DispenseTxnEntity>()
        coEvery { dispenseTxnDao.upsertPreservingId(capture(slot)) } returns 200L

        createRepo().handleZdrNewOrRefill(zdrMsg(quantity = "45"))

        assertEquals(45, slot.captured.targetCount)
    }

    @Test
    fun `NW stores messageControlId for ZDS echo`() = runTest(testDispatcher) {
        coEvery { drugMasterDao.getDrugByNdc("00527-8113-37", any()) } returns drug()
        val slot = slot<DispenseTxnEntity>()
        coEvery { dispenseTxnDao.upsertPreservingId(capture(slot)) } returns 200L

        createRepo().handleZdrNewOrRefill(zdrMsg(controlId = "MSG-CTRL-99"))

        assertEquals("MSG-CTRL-99", slot.captured.hl7MessageControlId)
    }

    @Test
    fun `NW high priority (S) mapped on txn`() = runTest(testDispatcher) {
        coEvery { drugMasterDao.getDrugByNdc("00527-8113-37", any()) } returns drug()
        val slot = slot<DispenseTxnEntity>()
        coEvery { dispenseTxnDao.upsertPreservingId(capture(slot)) } returns 200L

        createRepo().handleZdrNewOrRefill(zdrMsg(priority = "S"))

        assertEquals(com.dispensesure.retail.core.room.models.enums.TxnPriority.High, slot.captured.priority)
    }

    @Test
    fun `NW sets isComingFromHL7 true and status PARTIAL`() = runTest(testDispatcher) {
        coEvery { drugMasterDao.getDrugByNdc("00527-8113-37", any()) } returns drug()
        val slot = slot<DispenseTxnEntity>()
        coEvery { dispenseTxnDao.upsertPreservingId(capture(slot)) } returns 200L

        createRepo().handleZdrNewOrRefill(zdrMsg())

        assertTrue(slot.captured.isComingFromHL7 == true)
        assertEquals(CountStatus.PARTIAL, slot.captured.status)
    }

    @Test
    fun `NW shows new-rx notification`() = runTest(testDispatcher) {
        coEvery { drugMasterDao.getDrugByNdc("00527-8113-37", any()) } returns drug()
        coEvery { dispenseTxnDao.upsertPreservingId(any()) } returns 300L

        createRepo().handleZdrNewOrRefill(zdrMsg())

        coVerify(exactly = 1) { notifier.show(title = any(), message = any(), dispenseTxnId = 300L) }
    }

    @Test
    fun `NW zero quantity is rejected — no txn inserted`() = runTest(testDispatcher) {
        coEvery { drugMasterDao.getDrugByNdc("00527-8113-37", any()) } returns drug()

        createRepo().handleZdrNewOrRefill(zdrMsg(quantity = "0"))

        coVerify(exactly = 0) { dispenseTxnDao.upsertPreservingId(any()) }
    }

    @Test
    fun `NW resend of finished txn is ignored`() = runTest(testDispatcher) {
        coEvery { drugMasterDao.getDrugByNdc("00527-8113-37", any()) } returns drug()
        val finished = DispenseTxnEntity(txnId = 1L, status = CountStatus.COMPLETED, rxNo = "RX-001", refillNo = "1")
        // Code prefers transactionOrderId (ZDR-15) when present; zdrMsg() has orderId="ORDER-001"
        coEvery { dispenseTxnDao.getByTransactionOrderId("ORDER-001") } returns finished
        coEvery { dispenseTxnDao.getByRxNoAndMessageControlId("RX-001", "MSG001") } returns finished

        createRepo().handleZdrNewOrRefill(zdrMsg())

        coVerify(exactly = 0) { dispenseTxnDao.upsertPreservingId(any()) }
    }

    @Test
    fun `NW open existing txn is updated not re-inserted`() = runTest(testDispatcher) {
        coEvery { drugMasterDao.getDrugByNdc("00527-8113-37", any()) } returns drug()
        val open = DispenseTxnEntity(txnId = 55L, status = CountStatus.PARTIAL, rxNo = "RX-001", refillNo = "1")
        // Code prefers transactionOrderId (ZDR-15) when present; zdrMsg() has orderId="ORDER-001"
        coEvery { dispenseTxnDao.getByTransactionOrderId("ORDER-001") } returns open

        createRepo().handleZdrNewOrRefill(zdrMsg())

        coVerify(exactly = 0) { dispenseTxnDao.upsertPreservingId(any()) }
        coVerify(exactly = 1) { dispenseTxnDao.updateFromHl7Edit(55L, any(), any(), any(), any(), any()) }
    }

    // ════════════════════ NW — multi-drug ════════════════════

    @Test
    fun `NW multi-drug inserts one txn per drug`() = runTest(testDispatcher) {
        coEvery { drugMasterDao.getDrugByNdc("00527-8113-37", any()) } returns drug("00527-8113-37", "DRUG A", 11L)
        coEvery { drugMasterDao.getDrugByNdc("60505008404", any()) } returns drug("60505008404", "DRUG B", 12L)

        createRepo().handleZdrNewOrRefill(zdrMsgMultiDrug())

        coVerify(exactly = 2) { dispenseTxnDao.upsertPreservingId(any()) }
    }

    @Test
    fun `NW multi-drug skips drug with blank NDC, inserts the rest`() = runTest(testDispatcher) {
        // first NDC blank, second valid
        val msg = parseHl7Message(
            "${mshSegment("RDE", "O11", "MSG-BLANK")}\r" +
                "ZDR|D|1.0|MSG-BLANK|NW|^DRUG BLANK~60505008404^DRUG B|NDC|DOE^JANE|||PAT|||AB1234|RX-B|ORDER-B|30|1|R|5|A||TAB|||"
        )
        coEvery { drugMasterDao.getDrugByNdc("60505008404", any()) } returns drug("60505008404", "DRUG B", 12L)

        createRepo().handleZdrNewOrRefill(msg)

        coVerify(exactly = 1) { dispenseTxnDao.upsertPreservingId(any()) }
    }

    // ════════════════════ RF — refill ════════════════════

    @Test
    fun `RF creates new txn with derived fillNumber when ZDR has explicit fill`() = runTest(testDispatcher) {
        coEvery { drugMasterDao.getDrugByNdc("00527-8113-37", any()) } returns drug()
        val slot = slot<DispenseTxnEntity>()
        coEvery { dispenseTxnDao.upsertPreservingId(capture(slot)) } returns 210L

        createRepo().handleZdrNewOrRefill(zdrMsg(action = "RF", fillNumber = "2"))

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
        createRepo().handleZdrNewOrRefill(msg)

        assertEquals("4", slot.captured.refillNo) // prevRefill 3 + 1
    }

    @Test
    fun `RF with refillsAllowed zero shows exhausted notification and no txn`() = runTest(testDispatcher) {
        coEvery { drugMasterDao.getDrugByNdc("00527-8113-37", any()) } returns drug()

        createRepo().handleZdrNewOrRefill(zdrMsg(action = "RF", fillNumber = "2", refillsAllowed = "0"))

        coVerify(exactly = 0) { dispenseTxnDao.upsertPreservingId(any()) }
        coVerify(exactly = 1) { notifier.show(title = any(), message = any()) }
    }

    @Test
    fun `RF resend of finished fill is ignored`() = runTest(testDispatcher) {
        coEvery { drugMasterDao.getDrugByNdc("00527-8113-37", any()) } returns drug()
        val finished = DispenseTxnEntity(txnId = 9L, status = CountStatus.COMPLETED, rxNo = "RX-001", refillNo = "2")
        // Code prefers transactionOrderId (ZDR-15) when present; zdrMsg() has orderId="ORDER-001"
        coEvery { dispenseTxnDao.getByTransactionOrderId("ORDER-001") } returns finished
        coEvery { dispenseTxnDao.getByRxNoAndMessageControlId("RX-001", "MSG001") } returns finished

        createRepo().handleZdrNewOrRefill(zdrMsg(action = "RF", fillNumber = "2"))

        coVerify(exactly = 0) { dispenseTxnDao.upsertPreservingId(any()) }
    }

    // ════════════════════ CA — cancel ════════════════════

    @Test
    fun `CA cancels active txns by orderId`() = runTest(testDispatcher) {
        coEvery { dispenseTxnDao.cancelActiveTxnsByOrderId("ORDER-001") } returns listOf(77L)

        createRepo().handleZdrStatusChange(zdrMsg(action = "CA"))

        coVerify(exactly = 1) { dispenseTxnDao.cancelActiveTxnsByOrderId("ORDER-001") }
        verify { auditLogger.log(match { it is OrderCancelled && it.txnIds == listOf(77L) }) }
    }

    @Test
    fun `CA without orderId returns false`() = runTest(testDispatcher) {
        val msg = parseHl7Message(
            "${mshSegment("RDE", "O11", "MSG-CA-NORX")}\r" +
                "ZDR|D|1.0|MSG-CA-NORX|CA|00527-8113-37^DRUG|NDC|DOE^JANE|||PAT|||AB|||30|1|R|5|A||TAB|||"
        )
        val result = createRepo().handleZdrStatusChange(msg)

        assertFalse(result)
        coVerify(exactly = 0) { dispenseTxnDao.cancelActiveTxnsByOrderId(any()) }
    }

    // ════════════════════ HD — hold ════════════════════

    @Test
    fun `HD marks order ON_HOLD by orderId`() = runTest(testDispatcher) {
        coEvery { dispenseTxnDao.updateStatusByOrderIdIfPartial("ORDER-001", CountStatus.ON_HOLD, any()) } returns 1

        createRepo().handleZdrStatusChange(zdrMsg(action = "HD"))

        coVerify(exactly = 1) { dispenseTxnDao.updateStatusByOrderIdIfPartial("ORDER-001", CountStatus.ON_HOLD, any()) }
        verify { auditLogger.log(match { it is OrderHeld && it.orderId == "ORDER-001" }) }
    }

    @Test
    fun `HD without orderId returns false`() = runTest(testDispatcher) {
        val msg = parseHl7Message(
            "${mshSegment("RDE", "O11", "MSG-HD")}\r" +
                "ZDR|D|1.0|MSG-HD|HD|00527-8113-37^DRUG|NDC|DOE^JANE|||PAT|||AB|RX-HOLD||30|1|R|5|A||TAB|||"
        )
        val result = createRepo().handleZdrStatusChange(msg)

        assertFalse(result)
    }

    // ════════════════════ RL — release ════════════════════

    @Test
    fun `RL restores order to PARTIAL by orderId`() = runTest(testDispatcher) {
        coEvery { dispenseTxnDao.updateStatusByOrderIdIfPartialOrOnHold("ORDER-001", CountStatus.PARTIAL, any()) } returns 1

        createRepo().handleZdrStatusChange(zdrMsg(action = "RL"))

        coVerify { dispenseTxnDao.updateStatusByOrderIdIfPartialOrOnHold("ORDER-001", CountStatus.PARTIAL, any()) }
        verify { auditLogger.log(match { it is OrderReleased && it.orderId == "ORDER-001" }) }
    }

    // ════════════════════ DC — discontinue ════════════════════

    @Test
    fun `DC soft-deletes by orderId`() = runTest(testDispatcher) {
        coEvery { dispenseTxnDao.softDeleteByOrderId("ORDER-001", any()) } returns 1

        createRepo().handleZdrStatusChange(zdrMsg(action = "DC"))

        coVerify { dispenseTxnDao.softDeleteByOrderId("ORDER-001", any()) }
        verify { auditLogger.log(match { it is OrderDiscontinued && it.orderId == "ORDER-001" }) }
    }

    // ════════════════════ RL — blank orderId ════════════════════

    @Test
    fun `RL without orderId returns false`() = runTest(testDispatcher) {
        val msg = parseHl7Message(
            "${mshSegment("RDE", "O11", "MSG-RL-NOID")}\r" +
                "ZDR|D|1.0|MSG-RL-NOID|RL|00527-8113-37^DRUG|NDC|DOE^JANE|||PAT|||AB|RX-RL||30|1|R|5|A||TAB|||"
        )
        val result = createRepo().handleZdrStatusChange(msg)

        assertFalse(result)
        coVerify(exactly = 0) { dispenseTxnDao.updateStatusByOrderIdIfPartialOrOnHold(any(), any(), any()) }
    }

    // ════════════════════ DC — blank orderId ════════════════════

    @Test
    fun `DC without orderId returns false`() = runTest(testDispatcher) {
        val msg = parseHl7Message(
            "${mshSegment("RDE", "O11", "MSG-DC-NOID")}\r" +
                "ZDR|D|1.0|MSG-DC-NOID|DC|00527-8113-37^DRUG|NDC|DOE^JANE|||PAT|||AB|RX-DC||30|1|R|5|A||TAB|||"
        )
        val result = createRepo().handleZdrStatusChange(msg)

        assertFalse(result)
        coVerify(exactly = 0) { dispenseTxnDao.softDeleteByOrderId(any(), any()) }
    }

    // ════════════════════ XO — change order ════════════════════

    @Test
    fun `XO updates existing txn when found by transactionOrderId`() = runTest(testDispatcher) {
        coEvery { drugMasterDao.getDrugByNdc("00527-8113-37", any()) } returns drug()
        val existing = DispenseTxnEntity(txnId = 88L, status = CountStatus.PARTIAL, rxNo = "RX-001", refillNo = "1")
        coEvery { dispenseTxnDao.getByTransactionOrderId("ORDER-001") } returns existing

        val result = createRepo().handleZdrChangeOrder(zdrMsg(action = "XO"))

        assertTrue(result)
        coVerify(exactly = 1) { dispenseTxnDao.updateFromHl7Edit(88L, any(), any(), any(), null, any()) }
    }

    @Test
    fun `XO returns false when transactionOrderId not found in DB`() = runTest(testDispatcher) {
        coEvery { dispenseTxnDao.getByTransactionOrderId("ORDER-001") } returns null

        val result = createRepo().handleZdrChangeOrder(zdrMsg(action = "XO"))

        assertFalse(result)
        coVerify(exactly = 0) { dispenseTxnDao.updateFromHl7Edit(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `XO with blank orderId returns false`() = runTest(testDispatcher) {
        val msg = parseHl7Message(
            "${mshSegment("RDE", "O11", "MSG-XO-NOID")}\r" +
                "ZDR|D|1.0|MSG-XO-NOID|XO|00527-8113-37^DRUG|NDC|DOE^JANE|||PAT|||AB|RX-XO||30|1|R|5|A||TAB|||"
        )
        val result = createRepo().handleZdrChangeOrder(msg)

        assertFalse(result)
        coVerify(exactly = 0) { dispenseTxnDao.updateFromHl7Edit(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `XO with blank NDC returns false`() = runTest(testDispatcher) {
        val existing = DispenseTxnEntity(txnId = 91L, status = CountStatus.PARTIAL, rxNo = "RX-001", refillNo = "1")
        coEvery { dispenseTxnDao.getByTransactionOrderId("ORDER-001") } returns existing
        // message with blank drug NDC
        val msg = parseHl7Message(
            "${mshSegment("RDE", "O11", "MSG-XO-NONDC")}\r" +
                "ZDR|D|1.0|MSG-XO-NONDC|XO|^DRUG NAME|NDC|DOE^JANE|||PAT|||AB|RX-001|ORDER-001|30|1|R|5|A||TAB|||"
        )
        val result = createRepo().handleZdrChangeOrder(msg)

        assertFalse(result)
        coVerify(exactly = 0) { dispenseTxnDao.updateFromHl7Edit(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `XO with zero quantity returns false`() = runTest(testDispatcher) {
        val existing = DispenseTxnEntity(txnId = 92L, status = CountStatus.PARTIAL, rxNo = "RX-001", refillNo = "1")
        coEvery { dispenseTxnDao.getByTransactionOrderId("ORDER-001") } returns existing

        val result = createRepo().handleZdrChangeOrder(zdrMsg(action = "XO", quantity = "0"))

        assertFalse(result)
        coVerify(exactly = 0) { dispenseTxnDao.updateFromHl7Edit(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `XO decimal quantity is truncated to int`() = runTest(testDispatcher) {
        coEvery { drugMasterDao.getDrugByNdc("00527-8113-37", any()) } returns drug()
        val existing = DispenseTxnEntity(txnId = 93L, status = CountStatus.PARTIAL, rxNo = "RX-001", refillNo = "1")
        coEvery { dispenseTxnDao.getByTransactionOrderId("ORDER-001") } returns existing
        val qtySlot = slot<Int>()
        coEvery { dispenseTxnDao.updateFromHl7Edit(any(), any(), capture(qtySlot), any(), null, any()) } returns Unit

        createRepo().handleZdrChangeOrder(zdrMsg(action = "XO", quantity = "30.7"))

        assertEquals(30, qtySlot.captured)
    }

    // ════════════════════ NW/RF — fallback to rxNo when orderId absent ════════════════════

    @Test
    fun `NW with blank orderId falls back to rxNo+fillNo lookup`() = runTest(testDispatcher) {
        coEvery { drugMasterDao.getDrugByNdc("00527-8113-37", any()) } returns drug()
        // No orderId in ZDR-15 -> fallback to rxNo+fillNo
        val msg = parseHl7Message(
            "${mshSegment("RDE", "O11", "MSG-NW-NOID")}\r" +
                "ZDR|D|1.0|MSG-NW-NOID|NW|00527-8113-37^DRUG A|NDC|DOE^JANE|||PAT|||AB|RX-NOID||60|1|R|5|A||TAB|||"
        )
        createRepo().handleZdrNewOrRefill(msg)

        // Fallback path queries rxNo+fillNo when orderId absent
        coVerify { dispenseTxnDao.getByRxNoAndFillNo("RX-NOID", any()) }
    }

    @Test
    fun `RF with blank orderId falls back to rxNo+fillNo lookup`() = runTest(testDispatcher) {
        coEvery { drugMasterDao.getDrugByNdc("00527-8113-37", any()) } returns drug()
        val msg = parseHl7Message(
            "${mshSegment("RDE", "O11", "MSG-RF-NOID")}\r" +
                "ZDR|D|1.0|MSG-RF-NOID|RF|00527-8113-37^DRUG A|NDC|DOE^JANE|||PAT|||AB|RX-NOID||60|1|R|5|A||TAB|||"
        )
        createRepo().handleZdrNewOrRefill(msg)

        coVerify { dispenseTxnDao.getByRxNoAndFillNo("RX-NOID", any()) }
    }

    // ════════════════════ RF — update open existing txn ════════════════════

    @Test
    fun `RF open existing txn is updated not re-inserted`() = runTest(testDispatcher) {
        coEvery { drugMasterDao.getDrugByNdc("00527-8113-37", any()) } returns drug()
        val open = DispenseTxnEntity(txnId = 66L, status = CountStatus.PARTIAL, rxNo = "RX-001", refillNo = "2")
        coEvery { dispenseTxnDao.getByTransactionOrderId("ORDER-001") } returns open

        createRepo().handleZdrNewOrRefill(zdrMsg(action = "RF", fillNumber = "2"))

        coVerify(exactly = 0) { dispenseTxnDao.upsertPreservingId(any()) }
        coVerify(exactly = 1) { dispenseTxnDao.updateFromHl7Edit(66L, any(), any(), any(), any(), any()) }
    }

    // ════════════════════ unknown action ════════════════════

    @Test
    fun `unknown orderAction is ignored — no DB writes`() = runTest(testDispatcher) {
        createRepo().handleZdrNewOrRefill(zdrMsg(action = "XX"))

        coVerify(exactly = 0) { dispenseTxnDao.upsertPreservingId(any()) }
        coVerify(exactly = 0) { dispenseTxnDao.cancelActiveTxnsByOrderId(any()) }
    }

    // ════════════════════ drug not found ════════════════════

    @Test
    fun `NW drug not found — no txn, shows notification`() = runTest(testDispatcher) {
        coEvery { iDrugRepository.getDrugInfoByNdc(any()) } returns null

        createRepo().handleZdrNewOrRefill(zdrMsg())

        coVerify(exactly = 0) { dispenseTxnDao.upsertPreservingId(any()) }
        coVerify(exactly = 1) { notifier.show(title = any(), message = any()) }
    }

    // ════════════════════ no ZDR segment ════════════════════

    @Test
    fun `message with no ZDR segment is ignored`() = runTest(testDispatcher) {
        val msg = parseHl7Message(
            "${mshSegment("RDE", "O11", "MSG-NOZDR")}\r" +
                "ORC|NW|ORDER-001|RX-001||||||||NPI1234"
        )
        createRepo().handleZdrNewOrRefill(msg)

        coVerify(exactly = 0) { dispenseTxnDao.upsertPreservingId(any()) }
    }
}
