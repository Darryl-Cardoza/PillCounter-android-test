package com.dispensesure.retail.feature.hl7.data.repository

import android.content.Context
import com.dispensesure.retail.core.audit.AuditLogger
import com.dispensesure.retail.core.faceAuth.data.OperatorNameProvider
import com.dispensesure.retail.core.region.Region
import com.dispensesure.retail.core.region.RegionProvider
import com.dispensesure.retail.core.room.dao.DrugMasterDao
import com.dispensesure.retail.core.room.dao.StockBatchDao
import com.dispensesure.retail.core.room.dao.StockBottleInfoDao
import com.dispensesure.retail.core.room.dao.StockTxnDao
import com.dispensesure.retail.core.room.models.DrugMasterEntity
import com.dispensesure.retail.core.scanning.data.DrugImageDownloader
import com.dispensesure.retail.core.scanning.data.DrugRepository
import com.dispensesure.retail.core.scanning.data.DrugResolver
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
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.dispensesure.retail.core.audit.model.PmsRequestReceived
import com.dispensesure.retail.core.room.models.StockBatchEntity
import com.dispensesure.retail.core.room.models.dtos.StockBottleInfoDto
import com.dispensesure.retail.feature.hl7.util.Hl7Format
import org.rite.hl7.model.HL7Message

/**
 * Tests for the DISPENSESURE_NATIVE INR^U06 + ZDR (flowType=INVENTORY) path in StockHl7Repository.
 * Validates txn creation, ZDS ack sending, and failure/edge-case handling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StockHl7RepositoryZdrTest {

    private val regionProvider: RegionProvider = mockk { every { current } returns Region.US }
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var context: Context
    private lateinit var drugMasterDao: DrugMasterDao
    private lateinit var preferenceHelper: PreferenceHelper
    private lateinit var stockTxnDao: StockTxnDao
    private lateinit var stockBottleInfoDao: StockBottleInfoDao
    private lateinit var operatorNameProvider: OperatorNameProvider
    private lateinit var stockBatchDao: StockBatchDao
    private lateinit var hl7MessageSender: Hl7MessageSender
    private lateinit var drugRepository: DrugRepository
    private lateinit var notifier: Hl7Notifier
    private lateinit var drugImageDownloader: DrugImageDownloader
    private val auditLogger = mockk<AuditLogger>(relaxed = true)

    @Before
    fun setup() {
        stubAndroidLog()
        Dispatchers.setMain(testDispatcher)

        context = mockk(relaxed = true)
        drugMasterDao = mockk(relaxed = true)
        preferenceHelper = mockk(relaxed = true)
        stockTxnDao = mockk(relaxed = true)
        stockBottleInfoDao = mockk(relaxed = true)
        operatorNameProvider = mockk(relaxed = true)
        stockBatchDao = mockk(relaxed = true)
        hl7MessageSender = mockk(relaxed = true)
        drugRepository = mockk(relaxed = true)
        notifier = mockk(relaxed = true)
        drugImageDownloader = mockk(relaxed = true)

        every { context.getString(any()) } returns "x"
        every { context.getString(any(), *anyVararg()) } returns "x"

        coEvery { hl7MessageSender.sendRaw(any()) } returns Result.success(SUCCESS_ACK)
        coEvery { stockBatchDao.insert(any()) } returns 100L
        coEvery { stockBatchDao.getTutorialBatchIds() } returns emptyList()
        coEvery { stockTxnDao.upsertPreservingId(any()) } returns 1L
        coEvery { stockTxnDao.refreshBatchTotalNdcs(any()) } returns Unit
        coEvery { stockTxnDao.updateBatchUserName(any(), any()) } returns Unit
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createRepo() = StockHl7Repository(
        context = context,
        stockTxnDao = stockTxnDao,
        stockBottleInfoDao = stockBottleInfoDao,
        operatorNameProvider = operatorNameProvider,
        stockBatchDao = stockBatchDao,
        hl7MessageSender = hl7MessageSender,
        drugResolver = DrugResolver(drugMasterDao, drugRepository, drugImageDownloader, regionProvider),
        notifier = notifier,
        hl7ConfigProvider = Hl7ConfigProvider(context, preferenceHelper),
        regionProvider = regionProvider,
        auditLogger = auditLogger,
    )

    // ─────────────── ZDR INVENTORY message builders ───────────────

    private fun zdrInventoryMessage(
        controlId: String = "MSG-INV-1",
        ndc: String = "12345678901",
        drugName: String = "ASPIRIN",
        flowType: String = "I",
    ): HL7Message = parseHl7Message(
        "MSH|^~\\&|PMS|FAC|APP|STORE|20240101120000||INR^U06|$controlId|P|2.3.1\r" +
            "ZDR|$flowType|1.0|$controlId||$ndc^$drugName|NDC||||||||||||||"
    )

    private fun drugEntity(ndc: String = "12345678901", name: String = "ASPIRIN") =
        DrugMasterEntity(drugId = 5L, drugName = name, ndc = ndc)

    // ─────────────── handleZdrInventoryRequest — txn creation ───────────────

    @Test
    fun `ZDR INVENTORY creates stock batch when drug resolved locally`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc("12345678901", Region.US) } returns drugEntity()

        repo.handleZdrInventoryRequest(zdrInventoryMessage())

        coVerify { stockBatchDao.insert(any()) }
    }

    @Test
    fun `ZDR INVENTORY creates stock txn per resolved drug`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc("12345678901", Region.US) } returns drugEntity()

        repo.handleZdrInventoryRequest(zdrInventoryMessage())

        coVerify { stockTxnDao.upsertPreservingId(any()) }
    }

    @Test
    fun `ZDR INVENTORY skips batch when no drug can be resolved`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc(any(), any()) } returns null
        coEvery { drugRepository.getDrugInfoByNdc(any()) } returns null

        repo.handleZdrInventoryRequest(zdrInventoryMessage())

        coVerify(exactly = 0) { stockBatchDao.insert(any()) }
        verify { notifier.show(any(), any()) }
    }

    @Test
    fun `ZDR INVENTORY does not send when no drugs resolved`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc(any(), any()) } returns null
        coEvery { drugRepository.getDrugInfoByNdc(any()) } returns null

        repo.handleZdrInventoryRequest(zdrInventoryMessage())

        coVerify(exactly = 0) { hl7MessageSender.sendRaw(any()) }
    }

    @Test
    fun `ZDR INVENTORY refreshes batch NDC total and operator name`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc("12345678901", Region.US) } returns drugEntity()

        repo.handleZdrInventoryRequest(zdrInventoryMessage())

        coVerify { stockTxnDao.refreshBatchTotalNdcs(any()) }
        coVerify { stockTxnDao.updateBatchUserName(any(), any()) }
    }

    @Test
    fun `ZDR INVENTORY shows notification after successful txn creation`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc("12345678901", Region.US) } returns drugEntity()

        repo.handleZdrInventoryRequest(zdrInventoryMessage())

        verify { notifier.show(any(), any()) }
    }

    @Test
    fun `ZDR INVENTORY with two NDCs creates two txns`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc("11111111111", Region.US) } returns
            DrugMasterEntity(drugId = 10L, drugName = "DRUG A", ndc = "11111111111")
        coEvery { drugMasterDao.getDrugByNdc("22222222222", Region.US) } returns
            DrugMasterEntity(drugId = 20L, drugName = "DRUG B", ndc = "22222222222")

        val msg = parseHl7Message(
            "MSH|^~\\&|PMS|FAC|APP|STORE|20240101120000||INR^U06|MSG-MULTI|P|2.3.1\r" +
                "ZDR|I|1.0|MSG-MULTI||11111111111^DRUG A~22222222222^DRUG B|NDC||||||||||||||"
        )

        repo.handleZdrInventoryRequest(msg)

        coVerify(exactly = 2) { stockTxnDao.upsertPreservingId(any()) }
    }

    @Test
    fun `ZDR INVENTORY with two NDCs where one resolves still creates batch`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc("11111111111", Region.US) } returns
            DrugMasterEntity(drugId = 10L, drugName = "DRUG A", ndc = "11111111111")
        coEvery { drugMasterDao.getDrugByNdc("22222222222", Region.US) } returns null
        coEvery { drugRepository.getDrugInfoByNdc(any()) } returns null

        val msg = parseHl7Message(
            "MSH|^~\\&|PMS|FAC|APP|STORE|20240101120000||INR^U06|MSG-PARTIAL|P|2.3.1\r" +
                "ZDR|I|1.0|MSG-PARTIAL||11111111111^DRUG A~22222222222^DRUG B|NDC||||||||||||||"
        )

        repo.handleZdrInventoryRequest(msg)

        coVerify { stockBatchDao.insert(any()) }
        coVerify(exactly = 1) { stockTxnDao.upsertPreservingId(any()) }
    }

    // ─────────────── inbound request: audit, insert guard, no immediate response ───────────────

    @Test
    fun `an inventory request logs PmsRequestReceived even when nothing resolves`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc(any(), any()) } returns null
        coEvery { drugRepository.getDrugInfoByNdc(any()) } returns null

        repo.handleZdrInventoryRequest(zdrInventoryMessage())

        verify { auditLogger.log(match { it is PmsRequestReceived && it.drugCount == 1 && it.resolvedCount == 0 }) }
    }

    @Test
    fun `no stock transactions are inserted when the batch insert fails`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc("12345678901", Region.US) } returns drugEntity()
        coEvery { stockBatchDao.insert(any()) } returns -1L

        repo.handleZdrInventoryRequest(zdrInventoryMessage())

        coVerify(exactly = 0) { stockTxnDao.upsertPreservingId(any()) }
    }

    @Test
    fun `an inventory request does not send an immediate response`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc("12345678901", Region.US) } returns drugEntity()

        repo.handleZdrInventoryRequest(zdrInventoryMessage())

        coVerify(exactly = 0) { hl7MessageSender.sendRaw(any()) }
    }

    // ─────────────── buildInventoryCounts ───────────────

    private fun bottle(
        ndc: String?, lot: String? = "L1", expiry: String? = "2027-01",
        bottleQty: Int? = 0, looseQty: Int? = 0, packageQty: Int? = 100,
    ) = StockBottleInfoDto(
        bottleId = 1L, drugId = 1L, drugName = "D", ndc = ndc, lotNo = lot, expiry = expiry,
        bottleQty = bottleQty, looseQty = looseQty, packageQty = packageQty,
    )

    private val fixture = listOf(
        bottle("00093-0058-01", bottleQty = 2),
        bottle("00093-0058-01", bottleQty = 1),
        bottle("00054-0018-25", lot = "B", looseQty = 42, packageQty = 30),
        bottle("00054-0018-25", lot = "A", looseQty = 5, packageQty = 30),
        bottle("00001-0001-01", bottleQty = 0, looseQty = 0),
        bottle("00002-0002-02", bottleQty = null, looseQty = null, packageQty = null),
    )

    // The PMS sends its own request id in ZDR-15 ("IREQ-...") alongside MSH-10 ("IMSG-...") and
    // correlates its request to our response on ZDR-15. Storing only MSH-10 left it unable to
    // match the two, so the inventory request never reconciled.
    @Test
    fun `a native inventory request stores ZDR-15 and the response echoes it`() = runTest(testDispatcher) {
        val captured = slot<StockBatchEntity>()
        coEvery { stockBatchDao.insert(capture(captured)) } returns 1L
        coEvery { drugMasterDao.getDrugByNdc("00527-8113-37", any()) } returns
            drugEntity(ndc = "00527-8113-37", name = "DEXMETHYLPHENIDATE 40MG")

        val request = parseHl7Message(
            "MSH|^~\\&|HC Companion|PHARMACY|DISPENSESURE|ROBOT|20261103134537||RDE^O11|" +
                "IMSG20261103134537013|P|2.3.1\r" +
                "ZDR|I|1.0|IMSG20261103134537013|NW|00527-8113-37^DEXMETHYLPHENIDATE 40MG|NDC|||||" +
                "||||IREQ-20261103134537013|||||||||||Standard||||||||"
        )
        createRepo().handleZdrInventoryRequest(request)

        assertEquals("IMSG20261103134537013", captured.captured.requestIdFromPMS)
        assertEquals("IREQ-20261103134537013", captured.captured.orderReferenceFromPMS)

        // …and the response must carry it back, or the PMS still cannot reconcile.
        useNative()
        val storedBatch: StockBatchEntity = captured.captured
        coEvery { stockBatchDao.getById(1L) } returns storedBatch
        coEvery { stockBottleInfoDao.getByBatchId(1L) } returns listOf(bottle("00527-8113-37", bottleQty = 1))
        var sent = ""
        coEvery { hl7MessageSender.sendRaw(any()) } answers { sent = firstArg(); Result.success(SUCCESS_ACK) }

        createRepo().sendInventory(1L)

        val zds = sent.split('\r').first { it.startsWith("ZDS") }
        assertTrue("response must echo ZDR-15: $zds", zds.contains("IREQ-20261103134537013"))
        assertTrue("response must still carry the request message id: $zds", zds.contains("IMSG20261103134537013"))
    }

    // Field-by-field pin against a real response: the PMS reported a missing operator, a missing
    // drug name, and an ambiguous expiry ("11-03-2026" could be Mar 11 or Nov 3).
    @Test
    fun `native inventory response carries operator, drug name and an HL7 expiry`() = runTest(testDispatcher) {
        useNative()
        coEvery { stockBatchDao.getById(1L) } returns nativeBatch("IMSG-1").copy(userName = "Jane Doe")
        coEvery { stockBottleInfoDao.getByBatchId(1L) } returns listOf(
            bottle("00527-8113-37", lot = "10522", expiry = "11-03-2026", bottleQty = 2, packageQty = 100)
                .copy(drugName = "ETODOLAC 400MG TABLET", serialNo = "SN-9"),
        )
        var sent = ""
        coEvery { hl7MessageSender.sendRaw(any()) } answers { sent = firstArg(); Result.success(SUCCESS_ACK) }

        createRepo().sendInventory(1L)
        val zds = sent.split('\r').first { it.startsWith("ZDS") }

        assertTrue("drug name belongs in ZDS-07 as code^name: $zds",
            zds.contains("00527-8113-37^ETODOLAC 400MG TABLET"))
        assertTrue("operator must be named: $zds", zds.contains("Doe") && zds.contains("Jane"))
        // HL7 dates are yyyyMMdd; the stored MM-dd-yyyy display form is ambiguous on the wire.
        assertTrue("expiry must be HL7 yyyyMMdd: $zds", zds.contains("^10522^20261103"))
        assertFalse("the display date must not reach the wire: $zds", zds.contains("11-03-2026"))
        // ZDS-27 is a fixed 8-component structure — appending fields breaks positional parsers.
        val countRow = zds.split("|").last { it.contains("^10522^") }.split("~").first()
        assertEquals("count row must have exactly 8 components: $countRow", 8, countRow.split("^").size)
    }

    // The standard INU^U05 path sends controlled-count photos as image OBX rows. A controlled
    // count made under the native format must carry the same evidence, or the photos exist on
    // one wire format and silently not the other.
    @Test
    fun `native inventory response carries controlled count images`() = runTest(testDispatcher) {
        useNative()
        coEvery { stockBatchDao.getById(1L) } returns nativeBatch("MSG-IMG-1")
        coEvery { stockBottleInfoDao.getByBatchId(1L) } returns listOf(
            bottle("00093-0058-01", bottleQty = 1).copy(
                imagePaths = listOf("ctrl_a.jpg", "ctrl_b.jpg"),
            ),
        )
        var sent = ""
        coEvery { hl7MessageSender.sendRaw(any()) } answers { sent = firstArg(); Result.success(SUCCESS_ACK) }

        createRepo().sendInventory(1L)

        val zds = sent.split('\r').first { it.startsWith("ZDS") }
        assertTrue(zds, zds.contains("/images/ctrl_a.jpg^controlled_count^00093-0058-01"))
        assertTrue(zds, zds.contains("/images/ctrl_b.jpg^controlled_count^00093-0058-01"))
        assertFalse("separators must not stay escaped: $zds", zds.contains("\\S\\"))
    }

    // Regression: packageQty is nullable, so an unknown package size makes sealed PILLS 0 while
    // the bottles were really counted. Filtering on the pill total alone dropped that stock from
    // the count the PMS receives.
    @Test
    fun `sealed bottles with an unknown package size are still reported`() = runTest(testDispatcher) {
        coEvery { stockBottleInfoDao.getByBatchId(1L) } returns listOf(
            bottle("00099-0099-99", bottleQty = 3, packageQty = null),
        )

        val counts = createRepo().buildInventoryCounts(1L)

        assertEquals(1, counts.size)
        assertEquals(3, counts.single().sealedBottles)
        assertEquals(0, counts.single().sealed)
        assertEquals(0, counts.single().total)
    }

    @Test
    fun `inventory counts group by ndc lot expiry, drop empty groups, and sort`() = runTest(testDispatcher) {
        coEvery { stockBottleInfoDao.getByBatchId(1L) } returns fixture
        val counts = createRepo().buildInventoryCounts(1L)

        assertEquals(listOf("00054-0018-25" to "A", "00054-0018-25" to "B", "00093-0058-01" to "L1"), counts.map { it.ndc to it.lot })
        val sealed = counts.first { it.ndc == "00093-0058-01" }
        assertEquals(3, sealed.sealedBottles)
        assertEquals(300, sealed.sealed)
        assertEquals(300, sealed.total)
        val opened = counts.first { it.ndc == "00054-0018-25" && it.lot == "B" }
        assertEquals(1, opened.openedBottles)
        assertEquals(42, opened.opened)
        assertEquals(42, opened.total)
        counts.forEach { assertEquals(it.sealed + it.opened, it.total) }
    }

    @Test
    fun `one row with both sealed and loose quantity feeds both sides`() = runTest(testDispatcher) {
        coEvery { stockBottleInfoDao.getByBatchId(1L) } returns
            listOf(bottle("N1", bottleQty = 2, looseQty = 7, packageQty = 50))
        val c = createRepo().buildInventoryCounts(1L).single()
        assertEquals(listOf(107, 100, 2, 7, 1), listOf(c.total, c.sealed, c.sealedBottles, c.opened, c.openedBottles))
    }

    // ─────────────── sendInventory: native routing, counts, ACK ───────────────

    private fun nativeBatch(requestId: String?) = StockBatchEntity(batchId = 1L, requestIdFromPMS = requestId)

    private fun useNative() {
        every { preferenceHelper.getHl7Format() } returns Hl7Format.DISPENSESURE_NATIVE
    }

    @Test
    fun `the native inventory response carries the counts and no order reference`() = runTest(testDispatcher) {
        useNative()
        coEvery { stockBatchDao.getById(1L) } returns nativeBatch("MSG-INV-42")
        coEvery { stockBottleInfoDao.getByBatchId(1L) } returns fixture
        var sent = ""
        coEvery { hl7MessageSender.sendRaw(any()) } answers { sent = firstArg(); Result.success(SUCCESS_ACK) }

        createRepo().sendInventory(1L)

        val zds = sent.split('\r').first { it.startsWith("ZDS") }
        // Count rows must reach the PMS with real '^' component separators. The library
        // escapes them as \S\ inside its array setters, so the builder unescapes ZDS lines
        // after encoding — left escaped, the PMS cannot parse the quantities at all.
        // Expiry is normalized to HL7 form; "2027-01" is a month/year precision expiry (yyyyMM).
        assertTrue(zds, zds.contains("00093-0058-01^300^300^3^0^0^L1^202701"))
        assertFalse("ZDS count separators must not be escaped: $zds", zds.contains("\\S\\"))
        assertEquals("MSG-INV-42", zds.split('|')[4])
        coVerify(exactly = 1) { stockBatchDao.markBatchSynced(1L) }
    }

    @Test
    fun `a negative acknowledgement leaves the batch unsynced`() = runTest(testDispatcher) {
        useNative()
        coEvery { stockBatchDao.getById(1L) } returns nativeBatch("MSG-INV-42")
        coEvery { hl7MessageSender.sendRaw(any()) } returns
            Result.success("MSH|^~\\&|PMS|FAC|APP|STORE|20240101||ACK|M1|P|2.5\rMSA|AE|MSG1")

        createRepo().sendInventory(1L)

        coVerify(exactly = 0) { stockBatchDao.markBatchSynced(any()) }
    }

    @Test
    fun `a transport failure leaves the batch unsynced`() = runTest(testDispatcher) {
        useNative()
        coEvery { stockBatchDao.getById(1L) } returns nativeBatch("MSG-INV-42")
        coEvery { hl7MessageSender.sendRaw(any()) } returns Result.failure(RuntimeException("down"))

        createRepo().sendInventory(1L)

        coVerify(exactly = 0) { stockBatchDao.markBatchSynced(any()) }
    }

    @Test
    fun `a manual batch goes out as standard even while native is active`() = runTest(testDispatcher) {
        useNative()
        coEvery { stockBatchDao.getById(1L) } returns nativeBatch(null)
        coEvery { stockBottleInfoDao.getByBatchId(1L) } returns fixture
        val sent = mutableListOf<String>()
        coEvery { hl7MessageSender.sendRaw(any()) } answers { sent += firstArg<String>(); Result.success(SUCCESS_ACK) }

        createRepo().sendInventory(1L)

        assertTrue(sent.isNotEmpty())
        assertTrue(sent.none { m -> m.split('\r').any { it.startsWith("ZDS") } })
    }
}
