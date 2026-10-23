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
    fun `ZDR INVENTORY sends ZDS ack after txn creation`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc("12345678901", Region.US) } returns drugEntity()

        repo.handleZdrInventoryRequest(zdrInventoryMessage())

        coVerify { hl7MessageSender.sendRaw(any()) }
    }

    @Test
    fun `ZDR INVENTORY ZDS ack contains ZDS segment`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc("12345678901", Region.US) } returns drugEntity()

        var sentMessage = ""
        coEvery { hl7MessageSender.sendRaw(any()) } answers {
            sentMessage = firstArg()
            Result.success(SUCCESS_ACK)
        }

        repo.handleZdrInventoryRequest(zdrInventoryMessage())

        assert(sentMessage.contains("ZDS")) { "ZDS ack must contain ZDS segment, got: $sentMessage" }
    }

    @Test
    fun `ZDR INVENTORY ZDS ack does not contain ORC or PID or RXD`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc("12345678901", Region.US) } returns drugEntity()

        var sentMessage = ""
        coEvery { hl7MessageSender.sendRaw(any()) } answers {
            sentMessage = firstArg()
            Result.success(SUCCESS_ACK)
        }

        repo.handleZdrInventoryRequest(zdrInventoryMessage())

        val segments = sentMessage.split('\r').map { it.substringBefore('|') }.toSet()
        assert("ORC" !in segments) { "NATIVE inventory ack must not contain ORC" }
        assert("PID" !in segments) { "NATIVE inventory ack must not contain PID" }
        assert("RXD" !in segments) { "NATIVE inventory ack must not contain RXD" }
    }

    @Test
    fun `ZDR INVENTORY ZDS flowType is INVENTORY`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc("12345678901", Region.US) } returns drugEntity()

        var sentMessage = ""
        coEvery { hl7MessageSender.sendRaw(any()) } answers {
            sentMessage = firstArg()
            Result.success(SUCCESS_ACK)
        }

        repo.handleZdrInventoryRequest(zdrInventoryMessage())

        val zdsLine = sentMessage.split('\r').firstOrNull { it.startsWith("ZDS") }.orEmpty()
        val fields = zdsLine.split('|')
        assert(fields.size > 1 && fields[1] == "IR") {
            "ZDS-01 must be IR (inventory response), got: ${fields.getOrNull(1)}"
        }
    }

    @Test
    fun `ZDR INVENTORY ZDS requestMessageId matches inbound MSH-10`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc("12345678901", Region.US) } returns drugEntity()

        var sentMessage = ""
        coEvery { hl7MessageSender.sendRaw(any()) } answers {
            sentMessage = firstArg()
            Result.success(SUCCESS_ACK)
        }

        repo.handleZdrInventoryRequest(zdrInventoryMessage(controlId = "MSG-INV-42"))

        val zdsLine = sentMessage.split('\r').firstOrNull { it.startsWith("ZDS") }.orEmpty()
        val fields = zdsLine.split('|')
        // ZDS-04 is requestMessageId
        assert(fields.getOrNull(4) == "MSG-INV-42") {
            "ZDS-04 requestMessageId must be MSG-INV-42, got: ${fields.getOrNull(4)}"
        }
    }

    // ─────────────── drug resolution failures ───────────────

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
    fun `ZDR INVENTORY does not send ZDS when no drugs resolved`() = runTest(testDispatcher) {
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
    fun `ZDR INVENTORY send failure does not crash — notification still shown`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc("12345678901", Region.US) } returns drugEntity()
        coEvery { hl7MessageSender.sendRaw(any()) } returns Result.failure(RuntimeException("network error"))

        repo.handleZdrInventoryRequest(zdrInventoryMessage())

        // Notifier must still fire even when ack fails
        verify { notifier.show(any(), any()) }
    }

    // ─────────────── multiple NDCs in ZDR-05 ───────────────

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
}
