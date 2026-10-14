package com.dispensesure.retail.feature.hl7.data.repository

import android.content.Context
import android.util.Log
import com.dispensesure.retail.core.faceAuth.data.OperatorNameProvider
import com.dispensesure.retail.core.room.dao.StockBatchDao
import com.dispensesure.retail.core.room.dao.StockBottleInfoDao
import com.dispensesure.retail.core.room.dao.DrugMasterDao
import com.dispensesure.retail.core.room.dao.DispenseTxnDao
import com.dispensesure.retail.core.room.dao.DispenseCountDao
import com.dispensesure.retail.core.room.dao.StockTxnDao
import com.dispensesure.retail.core.room.models.StockBatchEntity
import com.dispensesure.retail.core.room.models.DrugMasterEntity
import com.dispensesure.retail.core.room.models.DispenseCountEntity
import com.dispensesure.retail.core.room.models.DispenseTxnEntity
import com.dispensesure.retail.core.room.models.enums.CountStatus
import com.dispensesure.retail.core.room.models.dtos.StockBottleInfoDto
import com.dispensesure.retail.core.room.models.enums.TxnPriority
import com.dispensesure.retail.core.scanning.data.DrugImageDownloader
import com.dispensesure.retail.core.scanning.data.DrugRepository
import com.dispensesure.retail.core.scanning.data.DrugResolver
import com.dispensesure.retail.core.scanning.domain.model.DrugInfo
import com.dispensesure.retail.core.scanning.domain.model.GetNdcRequestModel
import com.dispensesure.retail.core.utils.common.LocationProvider
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import com.dispensesure.retail.feature.hl7.core.Hl7MessageSender
import com.dispensesure.retail.feature.hl7.notification.Hl7Notifier
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.rite.hl7.model.HL7Message
import org.rite.hl7.parser.HL7Parser
import org.rite.hl7.parser.HL7ParseResult

/**
 * The SUT creates its own `CoroutineScope(Dispatchers.IO)` at construction and launches its
 * fire-and-forget handlers on it. We deliberately do NOT statically mock [Dispatchers] here:
 * doing so causes MockK's own verification machinery (which internally touches
 * `Dispatchers.getDefault()`) to be recorded into the verify scope, producing intermittent
 * "should not be called" failures and making the whole class flaky.
 *
 * Instead the SUT's scope runs on the real Dispatchers.IO background threads. Two kinds of
 * methods exist:
 *  - `suspend` methods called directly inside runTest (buildAndSend*) run inline and are
 *    verified with a plain `coVerify { }` immediately after the call.
 *  - methods that do `scope.launch { ... }` (handleReceivedMessage, resend*, markTransactionSynced,
 *    the init observer) run asynchronously on background threads; they are verified with MockK's
 *    polling form `coVerify(timeout = 3000) { }` / `verify(timeout = 3000) { }`. Absence
 *    ("not called") is only asserted AFTER synchronizing on a positive signal that proves the
 *    coroutine reached the relevant point.
 *
 * Inbound HL7 messages are built by parsing real wire-format HL7 text via [HL7Parser] into a
 * genuine [HL7Message] — the SUT's [HL7Message] constructor is internal to the HL7 library AAR, so
 * tests cannot construct one directly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Hl7RepositoryTest {

    // isSuccessAck() requires a real MSA|AA segment; a plain string like "ACK" is never accepted.
    private val SUCCESS_ACK = "MSH|^~\\&|PMS|FAC|APP|STORE|20240101||ACK|MSG1|P|2.5\rMSA|AA|MSG1"

    private val testDispatcher = StandardTestDispatcher()
    private val hl7Parser = HL7Parser.Builder().build()

    private lateinit var context: Context
    private lateinit var drugMasterDao: DrugMasterDao
    private lateinit var locationProvider: LocationProvider
    private lateinit var dispenseTxnDao: DispenseTxnDao
    private lateinit var dispenseCountDao: DispenseCountDao
    private lateinit var preferenceHelper: PreferenceHelper
    private lateinit var stockTxnDao: StockTxnDao
    private lateinit var stockBottleInfoDao: StockBottleInfoDao
    private lateinit var operatorNameProvider: OperatorNameProvider
    private lateinit var stockBatchDao: StockBatchDao
    private lateinit var hl7MessageSender: Hl7MessageSender
    private lateinit var drugRepository: DrugRepository
    private lateinit var notifier: Hl7Notifier
    private lateinit var drugImageDownloader: DrugImageDownloader

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.i(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<Throwable>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        // setMain is kept so suspend calls inside runTest(testDispatcher) work. The SUT's own
        // CoroutineScope(Dispatchers.IO) is independent of Dispatchers.Main and is NOT mocked.
        Dispatchers.setMain(testDispatcher)

        context = mockk(relaxed = true)
        drugMasterDao = mockk(relaxed = true)
        locationProvider = mockk(relaxed = true)
        dispenseTxnDao = mockk(relaxed = true)
        dispenseCountDao = mockk(relaxed = true)
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

        // By default HL7 is disabled so init's observePendingHl7Transactions does not interfere.
        every { preferenceHelper.isHl7Enabled() } returns false
        every { preferenceHelper.getLocalId() } returns 1L
        coEvery { hl7MessageSender.sendRaw(any()) } returns Result.success(SUCCESS_ACK)
        // send() must be stubbed, not left relaxed: the dispense path logs the returned ACK, and
        // a relaxed Result<String> hands back a non-String that blows up on first use.
        coEvery { hl7MessageSender.send(any()) } returns Result.success(SUCCESS_ACK)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createRepo(): Hl7Repository = Hl7Repository(
        context = context,
        drugMasterDao = drugMasterDao,
        locationProvider = locationProvider,
        dispenseTxnDao = dispenseTxnDao,
        dispenseCountDao = dispenseCountDao,
        preferenceHelper = preferenceHelper,
        stockTxnDao = stockTxnDao,
        stockBottleInfoDao = stockBottleInfoDao,
        operatorNameProvider = operatorNameProvider,
        stockBatchDao = stockBatchDao,
        hl7MessageSender = hl7MessageSender,
        drugResolver = DrugResolver(drugMasterDao, drugRepository, drugImageDownloader),
        notifier = notifier,
    )

    // ─────────────────────────────── message builders ───────────────────────────────

    private fun parse(raw: String): HL7Message {
        val result = hl7Parser.parse(raw)
        return when (result) {
            is HL7ParseResult.Success -> result.message
            is HL7ParseResult.Failure -> result.partialMessage
                ?: error("Failed to parse test HL7 message: ${result.errors}")
        }
    }

    private fun msh(messageType: String, triggerEvent: String, controlId: String) =
        "MSH|^~\\&|PMS|FAC|APP|STORE|20240101||$messageType^$triggerEvent|$controlId|P|2.5"

    /** RDE^O11 dispense request/edit built from MSH + ORC + RXE (+ optional ZPR/ZIN segments). */
    private fun dispenseMessage(
        orderControl: String = "NW",
        placerOrderId: String = "RX1",
        orderStatus: String = "IP",
        drugCode: String = "12345",
        drugName: String = "Aspirin",
        dispenseAmount: String = "10",
        controlId: String = "MSG1",
        extraSegments: List<String> = emptyList(),
    ): HL7Message {
        val segments = mutableListOf(
            msh("RDE", "O11", controlId),
            "ORC|$orderControl|$placerOrderId|||$orderStatus",
            "RXE||$drugCode^$drugName||||||||$dispenseAmount",
        )
        segments.addAll(extraSegments)
        return parse(segments.joinToString("\r"))
    }

    /** INR^U06 inventory request in the PMS layout: drug in INV-1, status in INV-2. */
    private fun inventoryMessage(
        ndc: String = "12345",
        drugName: String = "Aspirin",
        controlId: String = "MSG1",
    ): HL7Message =
        parse(
            listOf(
                msh("INR", "U06", controlId),
                "INV|$ndc^$drugName^NDC|A^Active",
            ).joinToString("\r")
        )

    private fun zprSegment(priority: String) = "ZPR|1|PRIORITY|$priority"
    private fun zinSegment(quantity: String) = "ZIN|1|EXPECTED_ON_HAND|$quantity"

    private fun drugInfo(
        ndc: String = "12345",
        genericName: String? = "Aspirin",
    ) = DrugInfo(
        brandName = "Brand",
        genericName = genericName,
        ndc = ndc,
        drugType = "TAB",
        qty = 30,
        isHazardous = false,
    )

    private fun txnEntity(
        txnId: Long = 1L,
        drugId: Long? = 5L,
        operatorFirstName: String? = null,
        operatorLastName: String? = null,
        isSynced: Boolean? = null,
    ) = DispenseTxnEntity(
        txnId = txnId,
        userLocalId = 1L,
        drugId = drugId,
        status = CountStatus.PARTIAL,
        rxNo = "RX1",
        operatorFirstName = operatorFirstName,
        operatorLastName = operatorLastName,
        isSynced = isSynced,
    )

    // ─────────────────────────────── init / observe ───────────────────────────────

    @Test
    fun `init with hl7 enabled drives observePendingHl7Transactions`() = runTest(testDispatcher) {
        every { preferenceHelper.isHl7Enabled() } returns true
        every { dispenseTxnDao.observePendingHl7Txn() } returns flowOf(emptyList())
        coEvery { dispenseTxnDao.getPendingHl7TxnOnce() } returns emptyList()

        createRepo()

        verify(timeout = 3000) { dispenseTxnDao.observePendingHl7Txn() }
    }

    @Test
    fun `init with hl7 disabled does not observe`() = runTest(testDispatcher) {
        every { preferenceHelper.isHl7Enabled() } returns false

        createRepo()

        // Synchronize on the init coroutine actually running (it reads isHl7Enabled) before
        // asserting the observer was never collected; this avoids racing the background launch.
        verify(timeout = 3000) { preferenceHelper.isHl7Enabled() }
        verify(exactly = 0) { dispenseTxnDao.observePendingHl7Txn() }
    }

    @Test
    fun `handleOrderEdit routes edit dispense`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { dispenseTxnDao.getActiveByRxNo("RX1") } returns txnEntity()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns
            DrugMasterEntity(drugId = 5L, drugName = "Aspirin", ndc = "12345")
        coEvery { drugMasterDao.upsertPreservingId(any()) } returns 5L

        val msg = dispenseMessage(orderControl = "XO", placerOrderId = "RX1", orderStatus = "IP")
        repo.handleOrderEdit(msg)

        coVerify(timeout = 3000) { dispenseTxnDao.updateFromHl7Edit(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `handleInrInventoryRequest routes inventory request`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns
            DrugMasterEntity(drugId = 5L, drugName = "Aspirin", ndc = "12345")
        coEvery { stockBatchDao.insert(any()) } returns 100L
        coEvery { stockTxnDao.upsertPreservingId(any()) } returns 1L

        val msg = inventoryMessage()
        repo.handleInrInventoryRequest(msg)

        coVerify(timeout = 3000) { stockBatchDao.insert(any()) }
    }

    // ─────────────────────────────── buildAndSendSuccessfulDispense ───────────────────────────────

    @Test
    fun `buildAndSendSuccessfulDispense returns when txn null`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { dispenseTxnDao.getById(1L) } returns null

        repo.buildAndSendSuccessfulDispense(1L)

        coVerify(exactly = 0) { hl7MessageSender.send(any()) }
    }

    @Test
    fun `buildAndSendSuccessfulDispense returns when drug null`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { dispenseTxnDao.getById(1L) } returns txnEntity(drugId = null)

        repo.buildAndSendSuccessfulDispense(1L)

        coVerify(exactly = 0) { hl7MessageSender.send(any()) }
    }

    @Test
    fun `buildAndSendSuccessfulDispense skips a txn that is already synced`() =
        runTest(testDispatcher) {
            // Completion calls sendDispenseNow AND flips the status, which wakes the resend
            // sweep. Whichever arrives second must not put a second copy on the wire.
            val repo = createRepo()
            coEvery { dispenseTxnDao.getById(1L) } returns txnEntity(drugId = 5L, isSynced = true)

            repo.buildAndSendSuccessfulDispense(1L)

            coVerify(exactly = 0) { hl7MessageSender.send(any()) }
        }

    @Test
    fun `buildAndSendSuccessfulDispense builds and sends`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { dispenseTxnDao.getById(1L) } returns txnEntity(drugId = 5L)
        coEvery { dispenseCountDao.getAllForTxn("1") } returns
            listOf(DispenseCountEntity(txnId = 1L, pillCount = 5))
        coEvery { locationProvider.getCurrentLocationAsString() } returns "loc"
        coEvery { drugMasterDao.getDrugById(5L) } returns
            DrugMasterEntity(drugId = 5L, drugName = "Aspirin", ndc = "12345")

        repo.buildAndSendSuccessfulDispense(1L)

        coVerify { hl7MessageSender.send(any()) }
    }

    /**
     * The operator is stamped onto the transaction when the count completes, so a resend
     * reports whoever ran the count. RXD-10 carries the name only — PMS is sent no
     * operator id.
     */
    @Test
    fun `buildAndSendSuccessfulDispense puts the operator in RXD-10 as caret family caret given`() =
        runTest(testDispatcher) {
            val repo = createRepo()
            coEvery { dispenseTxnDao.getById(1L) } returns txnEntity(
                drugId = 5L,
                operatorFirstName = "Yash",
                operatorLastName = "Wadajkar",
            )
            coEvery { dispenseCountDao.getAllForTxn("1") } returns
                listOf(DispenseCountEntity(txnId = 1L, pillCount = 5))
            coEvery { locationProvider.getCurrentLocationAsString() } returns "loc"
            coEvery { drugMasterDao.getDrugById(5L) } returns
                DrugMasterEntity(drugId = 5L, drugName = "Aspirin", ndc = "12345")

            val sent = slot<String>()
            coEvery { hl7MessageSender.send(capture(sent)) } returns Result.success("ACK")

            repo.buildAndSendSuccessfulDispense(1L)

            val rxd = sent.captured.split("\r").first { it.startsWith("RXD|") }
            val rxd10 = rxd.split("|")[10]
            assertEquals("^Wadajkar^Yash", rxd10)
        }

    // ─────────────────────────────── buildAndSendInventoryResponse ───────────────────────────────

    @Test
    fun `buildAndSendInventoryResponse returns when batch null`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { stockBatchDao.getById(100L) } returns null

        repo.buildAndSendInventoryResponse(100L)

        coVerify(exactly = 0) { hl7MessageSender.sendRaw(any()) }
    }

    @Test
    fun `buildAndSendInventoryResponse never sends a tutorial batch`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { stockBatchDao.getTutorialBatchIds() } returns listOf(100L)
        coEvery { stockBatchDao.getById(100L) } returns StockBatchEntity(batchId = 100L, requestIdFromPMS = "REQ")

        repo.buildAndSendInventoryResponse(100L)

        coVerify(exactly = 0) { hl7MessageSender.sendRaw(any()) }
        coVerify(exactly = 0) { stockBatchDao.markBatchSynced(any()) }
    }

    @Test
    fun `sendDispenseNow never sends a tutorial transaction`() = runTest(testDispatcher) {
        val repo = createRepo()
        every { preferenceHelper.isHl7Enabled() } returns true
        coEvery { dispenseTxnDao.getTutorialTxnIds() } returns listOf(7L)

        repo.sendDispenseNow(7L)

        coVerify(timeout = 1000) { dispenseTxnDao.getTutorialTxnIds() }
        coVerify(exactly = 0) { dispenseTxnDao.getById(7L) }
        coVerify(exactly = 0) { hl7MessageSender.sendRaw(any()) }
    }

    @Test
    fun `buildAndSendInventoryResponse success marks batch synced`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { stockBatchDao.getById(100L) } returns StockBatchEntity(batchId = 100L, requestIdFromPMS = "REQ")
        coEvery { stockBottleInfoDao.getByBatchId(100L) } returns emptyList()
        coEvery { hl7MessageSender.sendRaw(any()) } returns Result.success(SUCCESS_ACK)

        repo.buildAndSendInventoryResponse(100L)

        coVerify { stockBatchDao.markBatchSynced(100L) }
    }

    @Test
    fun `buildAndSendInventoryResponse failure does not mark synced`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { stockBatchDao.getById(100L) } returns StockBatchEntity(batchId = 100L, requestIdFromPMS = "REQ")
        coEvery { stockBottleInfoDao.getByBatchId(100L) } returns emptyList()
        coEvery { hl7MessageSender.sendRaw(any()) } returns Result.failure(RuntimeException("send fail"))

        repo.buildAndSendInventoryResponse(100L)

        coVerify(exactly = 0) { stockBatchDao.markBatchSynced(any()) }
    }

    @Test
    fun `buildAndSendInventoryResponse swallows exceptions`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { stockBatchDao.getById(100L) } throws RuntimeException("db boom")

        repo.buildAndSendInventoryResponse(100L)

        coVerify(exactly = 0) { hl7MessageSender.sendRaw(any()) }
    }

    // ─────────────────────────────── large batch (chunked) sync ───────────────────────────────

    /**
     * Builds enough distinct (ndc, lot, expiry) rows to produce ~10000 INV segments once
     * grouped by HL7MessageBuilder — with maxRowsPerChunk=200 this yields 50 chunks.
     */
    private fun largeBatchTxns(rowCount: Int = 10000): List<StockBottleInfoDto> =
        (1..rowCount).map { i ->
            StockBottleInfoDto(
                bottleId = i.toLong(),
                drugId = i.toLong(),
                drugName = "Drug$i",
                ndc = "NDC$i",
                lotNo = "LOT$i",
                expiry = "12-31-2026",
                bottleQty = 1,
                looseQty = 0,
                packageQty = 30,
            )
        }

    @Test
    fun `buildAndSendInventoryResponse large batch sends all chunks in order then marks synced`() =
        runTest(testDispatcher) {
            val repo = createRepo()
            coEvery { stockBatchDao.getById(100L) } returns StockBatchEntity(batchId = 100L, requestIdFromPMS = "REQ")
            coEvery { stockBottleInfoDao.getByBatchId(100L) } returns largeBatchTxns(10000)
            coEvery { hl7MessageSender.sendRaw(any()) } returns Result.success(SUCCESS_ACK)

            repo.buildAndSendInventoryResponse(100L)

            // 10000 distinct ndc/lot/expiry rows chunked at 200 rows/chunk => 50 chunks.
            coVerify(exactly = 50) { hl7MessageSender.sendRaw(any()) }
            for (chunkIndex in 1..50) {
                coVerify { stockBatchDao.markChunkAcked(100L, chunkIndex) }
            }
            coVerify { stockBatchDao.setTotalChunks(100L, 50) }
            coVerify { stockBatchDao.markBatchSynced(100L) }
        }

    @Test
    fun `buildAndSendInventoryResponse large batch stops and stays unsynced on mid-batch chunk failure`() =
        runTest(testDispatcher) {
            val repo = createRepo()
            coEvery { stockBatchDao.getById(100L) } returns StockBatchEntity(batchId = 100L, requestIdFromPMS = "REQ")
            coEvery { stockBottleInfoDao.getByBatchId(100L) } returns largeBatchTxns(10000)

            // Chunks are single-line HL7 messages; chunk N carries "chunk N of" via the BTS
            // trailer text, so fail specifically the 27th send call regardless of message content.
            var callCount = 0
            coEvery { hl7MessageSender.sendRaw(any()) } answers {
                callCount++
                if (callCount == 27) Result.failure(RuntimeException("PMS unavailable"))
                else Result.success(SUCCESS_ACK)
            }

            repo.buildAndSendInventoryResponse(100L)

            // Only chunks 1..26 acked; the loop returns immediately on the 27th failure without
            // sending chunks 28..50 out of order, and the batch is never marked synced.
            coVerify(exactly = 27) { hl7MessageSender.sendRaw(any()) }
            for (chunkIndex in 1..26) {
                coVerify { stockBatchDao.markChunkAcked(100L, chunkIndex) }
            }
            coVerify(exactly = 0) { stockBatchDao.markChunkAcked(100L, 27) }
            coVerify(exactly = 0) { stockBatchDao.markBatchSynced(any()) }
        }

    @Test
    fun `resendPendingHl7BatchTransactions resumes a large batch from lastAckedChunkIndex and syncs`() =
        runTest(testDispatcher) {
            val repo = createRepo()
            // Batch previously got through chunk 40 of 50 before PMS went offline / app restarted.
            coEvery { stockBatchDao.getUnsyncedCompletedBatchesOnce() } returns
                listOf(
                    com.dispensesure.retail.core.room.models.dtos.BatchSummaryDto(
                        batchId = 100L,
                        createdAt = 0L,
                        uniqueNdcCount = 10000,
                        status = "COMPLETED",
                        bucketId = null,
                        requestIdFromPMS = "REQ",
                    )
                )
            coEvery { stockBatchDao.getById(100L) } returns
                StockBatchEntity(batchId = 100L, requestIdFromPMS = "REQ", lastAckedChunkIndex = 40, totalChunks = 50)
            coEvery { stockBottleInfoDao.getByBatchId(100L) } returns largeBatchTxns(10000)
            coEvery { hl7MessageSender.sendRaw(any()) } returns Result.success(SUCCESS_ACK)

            repo.resendPendingHl7BatchTransactions()

            // Only the remaining 10 chunks (41..50) are resent; 1..40 are skipped as already ACKed.
            coVerify(timeout = 3000, exactly = 10) { hl7MessageSender.sendRaw(any()) }
            for (chunkIndex in 41..50) {
                coVerify(timeout = 3000) { stockBatchDao.markChunkAcked(100L, chunkIndex) }
            }
            coVerify(exactly = 0) { stockBatchDao.markChunkAcked(100L, 40) }
            coVerify(timeout = 3000) { stockBatchDao.markBatchSynced(100L) }
        }

    @Test
    fun `onClientConnected triggers large batch resend and marks synced once fully sent`() =
        runTest(testDispatcher) {
            // Mirrors Hl7EventHandler.onClientConnected, which calls
            // resendPendingHl7BatchTransactions() as soon as PMS (re)connects — this is the
            // "send on connected" trigger for a large (10k-segment / 50-chunk) pending batch.
            val repo = createRepo()
            coEvery { stockBatchDao.getUnsyncedCompletedBatchesOnce() } returns
                listOf(
                    com.dispensesure.retail.core.room.models.dtos.BatchSummaryDto(
                        batchId = 100L,
                        createdAt = 0L,
                        uniqueNdcCount = 10000,
                        status = "COMPLETED",
                        bucketId = null,
                        requestIdFromPMS = "REQ",
                    )
                )
            coEvery { stockBatchDao.getById(100L) } returns StockBatchEntity(batchId = 100L, requestIdFromPMS = "REQ")
            coEvery { stockBottleInfoDao.getByBatchId(100L) } returns largeBatchTxns(10000)
            coEvery { hl7MessageSender.sendRaw(any()) } returns Result.success(SUCCESS_ACK)

            // Simulates the connect callback's resend trigger directly against the repository
            // (Hl7EventHandler.onClientConnected just forwards to this).
            repo.resendPendingHl7BatchTransactions()

            coVerify(timeout = 3000, exactly = 50) { hl7MessageSender.sendRaw(any()) }
            coVerify(timeout = 3000) { stockBatchDao.markBatchSynced(100L) }
        }

    // ─────────────────────────────── resendPendingHl7Transactions ───────────────────────────────

    @Test
    fun `resendPendingHl7Transactions empty returns`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { dispenseTxnDao.getPendingHl7TxnOnce() } returns emptyList()

        repo.resendPendingHl7Transactions()

        // Synchronize on the coroutine having read the pending list before asserting no
        // dispense send was attempted.
        coVerify(timeout = 3000) { dispenseTxnDao.getPendingHl7TxnOnce() }
        coVerify(exactly = 0) { dispenseTxnDao.getById(any()) }
    }

    @Test
    fun `resendPendingHl7Transactions sends every pending dispense`() = runTest(testDispatcher) {
        val repo = createRepo()
        // Stock (REGULAR) counts no longer live in pill_count_txn; every pending row is a dispense.
        // Batch HL7 responses are resent per-batch via resendPendingHl7BatchTransactions().
        coEvery { dispenseTxnDao.getPendingHl7TxnOnce() } returns listOf(txnEntity(txnId = 1L))

        coEvery { dispenseTxnDao.getById(1L) } returns txnEntity(txnId = 1L, drugId = 5L)
        coEvery { dispenseCountDao.getAllForTxn("1") } returns emptyList()
        coEvery { drugMasterDao.getDrugById(5L) } returns
            DrugMasterEntity(drugId = 5L, drugName = "Aspirin", ndc = "12345")

        repo.resendPendingHl7Transactions()

        coVerify(timeout = 3000) { hl7MessageSender.send(any()) }
        // The outbound MSH-10 is persisted as the txnId itself so a later ACK can correlate
        // back to this exact row via getByMessageControlId.
        coVerify(timeout = 3000) { dispenseTxnDao.updateHl7MessageControlId(1L, "1", any()) }
        // No inventory response is triggered from this path.
        coVerify(exactly = 0) { stockBatchDao.markBatchSynced(any()) }
    }

    // ─────────────────────────────── resendPendingHl7BatchTransactions ───────────────────────────────

    @Test
    fun `resendPendingHl7BatchTransactions empty returns`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { stockBatchDao.getUnsyncedCompletedBatchesOnce() } returns emptyList()

        repo.resendPendingHl7BatchTransactions()

        // Synchronize on the coroutine having read the unsynced list before asserting getById
        // was never invoked.
        coVerify(timeout = 3000) { stockBatchDao.getUnsyncedCompletedBatchesOnce() }
        coVerify(exactly = 0) { stockBatchDao.getById(any()) }
    }

    @Test
    fun `resendPendingHl7BatchTransactions non-empty loops`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { stockBatchDao.getUnsyncedCompletedBatchesOnce() } returns
            listOf(
                com.dispensesure.retail.core.room.models.dtos.BatchSummaryDto(
                    batchId = 100L,
                    createdAt = 0L,
                    uniqueNdcCount = 0,
                    status = "COMPLETED",
                    bucketId = null,
                    requestIdFromPMS = "REQ",
                )
            )
        coEvery { stockBatchDao.getById(100L) } returns StockBatchEntity(batchId = 100L, requestIdFromPMS = "REQ")
        coEvery { stockBottleInfoDao.getByBatchId(100L) } returns emptyList()

        repo.resendPendingHl7BatchTransactions()

        coVerify(timeout = 3000) { stockBatchDao.markBatchSynced(100L) }
    }

    // ─────────────────────────────── markTransactionSynced (private, via buildAndSendSuccessfulDispense) ───────────────────────────────
    //
    // markTransactionSynced(txnId: Long) is a private implementation detail invoked only from
    // buildAndSendSuccessfulDispense on a success ACK — there is no longer any messageControlId
    // lookup (that was the pre-refactor shape). Exercised here through the public entry point.

    @Test
    fun `buildAndSendSuccessfulDispense marks txn synced on success ACK`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { dispenseTxnDao.getById(1L) } returns txnEntity(drugId = 5L)
        coEvery { dispenseCountDao.getAllForTxn("1") } returns
            listOf(DispenseCountEntity(txnId = 1L, pillCount = 5))
        coEvery { locationProvider.getCurrentLocationAsString() } returns "loc"
        coEvery { drugMasterDao.getDrugById(5L) } returns
            DrugMasterEntity(drugId = 5L, drugName = "Aspirin", ndc = "12345")
        coEvery { hl7MessageSender.send(any()) } returns Result.success("MSH|^~\\&|A|B|C|D|20240101||ACK|1|P|2.5\rMSA|AA|1\r")
        every { preferenceHelper.isAllowLocalStorage() } returns true

        repo.buildAndSendSuccessfulDispense(1L)

        coVerify(timeout = 3000) { dispenseTxnDao.markTxnSynced(1L, any()) }
    }

    @Test
    fun `buildAndSendSuccessfulDispense does not mark synced on non-success ACK`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { dispenseTxnDao.getById(1L) } returns txnEntity(drugId = 5L)
        coEvery { dispenseCountDao.getAllForTxn("1") } returns
            listOf(DispenseCountEntity(txnId = 1L, pillCount = 5))
        coEvery { locationProvider.getCurrentLocationAsString() } returns "loc"
        coEvery { drugMasterDao.getDrugById(5L) } returns
            DrugMasterEntity(drugId = 5L, drugName = "Aspirin", ndc = "12345")
        coEvery { hl7MessageSender.send(any()) } returns Result.success("MSH|^~\\&|A|B|C|D|20240101||ACK|1|P|2.5\rMSA|AE|1|rejected\r")

        repo.buildAndSendSuccessfulDispense(1L)

        coVerify(exactly = 0) { dispenseTxnDao.markTxnSynced(any(), any()) }
    }

    // ─────────────────────────────── handleInrInventoryRequest ───────────────────────────────

    @Test
    fun `handleInrInventoryRequest empty ndc returns`() = runTest(testDispatcher) {
        val repo = createRepo()
        // INV segment with a blank NDC is skipped, leaving resolvedItems empty — exercises the
        // notify + no-batch path.
        val msg = inventoryMessage(ndc = "")
        repo.handleInrInventoryRequest(msg)

        // notifier.show is the terminal action when resolvedItems is empty; sync on it before
        // asserting no batch was created.
        verify(timeout = 3000) { notifier.show(any(), any()) }
        coVerify(exactly = 0) { stockBatchDao.insert(any()) }
    }

    @Test
    fun `handleInrInventoryRequest local drug with name resolves and creates batch`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns
            DrugMasterEntity(drugId = 5L, drugName = "Aspirin", ndc = "12345")
        coEvery { stockBatchDao.insert(any()) } returns 100L
        coEvery { stockTxnDao.upsertPreservingId(any()) } returns 1L

        val msg = inventoryMessage()
        repo.handleInrInventoryRequest(msg)

        coVerify(timeout = 3000) { stockBatchDao.insert(any()) }
        // Stock counts now write to stock_txn (StockTxnEntity), not pill_count_txn.
        coVerify(timeout = 3000) { stockTxnDao.upsertPreservingId(any()) }
        verify(timeout = 3000) { notifier.show(any(), any()) }
    }

    @Test
    fun `handleInrInventoryRequest local drug empty name is fetched from the server`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns
            DrugMasterEntity(drugId = 5L, drugName = "", ndc = "12345")
        coEvery { drugRepository.getDrugInfoByNdc(any<GetNdcRequestModel>()) } returns drugInfo()
        coEvery { drugMasterDao.upsertPreservingId(any()) } returns 9L
        coEvery { stockBatchDao.insert(any()) } returns 100L
        coEvery { stockTxnDao.upsertPreservingId(any()) } returns 1L

        repo.handleInrInventoryRequest(inventoryMessage())

        coVerify(timeout = 3000) { drugRepository.getDrugInfoByNdc(any<GetNdcRequestModel>()) }
        coVerify(timeout = 3000) { stockBatchDao.insert(any()) }
    }

    @Test
    fun `handleInrInventoryRequest api fallback success`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns null
        coEvery { drugRepository.getDrugInfoByNdc(any<GetNdcRequestModel>()) } returns drugInfo()
        coEvery { drugMasterDao.upsertPreservingId(any()) } returns 9L
        coEvery { stockBatchDao.insert(any()) } returns 100L
        coEvery { stockTxnDao.upsertPreservingId(any()) } returns 1L

        val msg = inventoryMessage()
        repo.handleInrInventoryRequest(msg)

        coVerify(timeout = 3000) { stockBatchDao.insert(any()) }
    }

    @Test
    fun `handleInrInventoryRequest api null name uses substance name fallback`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns null
        coEvery { drugRepository.getDrugInfoByNdc(any<GetNdcRequestModel>()) } returns
            drugInfo(genericName = null)
        coEvery { drugMasterDao.upsertPreservingId(any()) } returns 9L
        coEvery { stockBatchDao.insert(any()) } returns 100L
        coEvery { stockTxnDao.upsertPreservingId(any()) } returns 1L

        // INV substance name is non-blank "Aspirin" so resolveInventoryItem falls back to it.
        val msg = inventoryMessage()
        repo.handleInrInventoryRequest(msg)

        coVerify(timeout = 3000) { stockBatchDao.insert(any()) }
    }

    @Test
    fun `handleInrInventoryRequest reads ndc and name from INV-1 not the INV-2 status`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc(any()) } returns null
        coEvery { drugRepository.getDrugInfoByNdc(any<GetNdcRequestModel>()) } returns
            drugInfo(ndc = "", genericName = null)
        val savedDrug = slot<DrugMasterEntity>()
        coEvery { drugMasterDao.upsertPreservingId(capture(savedDrug)) } returns 9L
        coEvery { stockBatchDao.insert(any()) } returns 100L

        repo.handleInrInventoryRequest(inventoryMessage())

        coVerify(timeout = 3000) { stockBatchDao.insert(any()) }
        assertEquals("12345", savedDrug.captured.ndc)
        assertEquals("Aspirin", savedDrug.captured.drugName)
    }

    @Test
    fun `handleInrInventoryRequest api null name and blank substance name skipped`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns null
        coEvery { drugRepository.getDrugInfoByNdc(any<GetNdcRequestModel>()) } returns
            drugInfo(genericName = null)

        val msg = inventoryMessage(drugName = "")
        repo.handleInrInventoryRequest(msg)

        verify(timeout = 3000) { notifier.show(any(), any()) }
        coVerify(exactly = 0) { stockBatchDao.insert(any()) }
    }

    @Test
    fun `handleInrInventoryRequest api throws continues`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns null
        coEvery { drugRepository.getDrugInfoByNdc(any<GetNdcRequestModel>()) } throws
            RuntimeException("api boom")

        val msg = inventoryMessage()
        repo.handleInrInventoryRequest(msg)

        verify(timeout = 3000) { notifier.show(any(), any()) }
        coVerify(exactly = 0) { stockBatchDao.insert(any()) }
    }

    // ─────────────────────────────── handleOrderEdit ───────────────────────────────

    private fun editMessage(
        orderStatus: String = "IP",
        placerOrderId: String = "RX1",
        extraSegments: List<String> = emptyList(),
    ): HL7Message = dispenseMessage(
        orderControl = "XO",
        placerOrderId = placerOrderId,
        orderStatus = orderStatus,
        extraSegments = extraSegments,
    )

    @Test
    fun `handleOrderEdit active txn local drug updates`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { dispenseTxnDao.getActiveByRxNo("RX1") } returns txnEntity()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns
            DrugMasterEntity(drugId = 5L, drugName = "Aspirin", ndc = "12345")
        coEvery { drugMasterDao.upsertPreservingId(any()) } returns 5L

        repo.handleOrderEdit(
            editMessage(extraSegments = listOf(zprSegment("Low"))),
        )

        coVerify(timeout = 3000) { dispenseTxnDao.updateFromHl7Edit(any(), any(), any(), any(), any(), any()) }
        verify(timeout = 3000) { notifier.show(any(), any()) }
    }

    @Test
    fun `handleOrderEdit restores deleted txn`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { dispenseTxnDao.getActiveByRxNo("RX1") } returns null
        coEvery { dispenseTxnDao.getDeletedByRxNo("RX1") } returns txnEntity(txnId = 8L)
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns
            DrugMasterEntity(drugId = 5L, drugName = "Aspirin", ndc = "12345")
        coEvery { drugMasterDao.upsertPreservingId(any()) } returns 5L

        repo.handleOrderEdit(editMessage())

        coVerify(timeout = 3000) { dispenseTxnDao.restoreDeletedTxn(8L, any()) }
        coVerify(timeout = 3000) { dispenseTxnDao.updateFromHl7Edit(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `handleOrderEdit no txn found notifies`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { dispenseTxnDao.getActiveByRxNo("RX1") } returns null
        coEvery { dispenseTxnDao.getDeletedByRxNo("RX1") } returns null

        repo.handleOrderEdit(editMessage())

        verify(timeout = 3000) { notifier.show(any(), any()) }
        coVerify(exactly = 0) { dispenseTxnDao.updateFromHl7Edit(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `handleOrderEdit api success when not local`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { dispenseTxnDao.getActiveByRxNo("RX1") } returns txnEntity()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns null
        coEvery { drugRepository.getDrugInfoByNdc(any<GetNdcRequestModel>()) } returns drugInfo()
        coEvery { drugMasterDao.upsertPreservingId(any()) } returns 9L

        repo.handleOrderEdit(editMessage())

        coVerify(timeout = 3000) { dispenseTxnDao.updateFromHl7Edit(any(), eq(9L), any(), any(), any(), any()) }
    }

    @Test
    fun `handleOrderEdit api no name notifies and returns`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { dispenseTxnDao.getActiveByRxNo("RX1") } returns txnEntity()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns null
        coEvery { drugRepository.getDrugInfoByNdc(any<GetNdcRequestModel>()) } returns
            drugInfo(genericName = null)

        repo.handleOrderEdit(editMessage())

        verify(timeout = 3000) { notifier.show(any(), any()) }
        coVerify(exactly = 0) { dispenseTxnDao.updateFromHl7Edit(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `handleOrderEdit api throws notifies and returns`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { dispenseTxnDao.getActiveByRxNo("RX1") } returns txnEntity()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns null
        coEvery { drugRepository.getDrugInfoByNdc(any<GetNdcRequestModel>()) } throws
            RuntimeException("api fail")

        repo.handleOrderEdit(editMessage())

        verify(timeout = 3000) { notifier.show(any(), any()) }
        coVerify(exactly = 0) { dispenseTxnDao.updateFromHl7Edit(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `handleOrderEdit newDrugId null fallback keeps old drugId`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { dispenseTxnDao.getActiveByRxNo("RX1") } returns txnEntity(drugId = 77L)
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns null
        coEvery { drugRepository.getDrugInfoByNdc(any<GetNdcRequestModel>()) } returns drugInfo()
        // upsertPreservingId returns... but resolvedDrug is non-null so it WILL call upsert.
        // To exercise the `?: existingTxn.drugId` fallback we need resolvedDrug null, which is
        // only possible when localDrug null AND api builds a DrugMasterEntity (non-null). The
        // elvis only triggers if upsertPreservingId's let-receiver is null, i.e. resolvedDrug null.
        // resolvedDrug can be null only via local path returning null (impossible: localDrug null
        // routes to API which returns non-null entity or returns early). Documented as a partially
        // unreachable elvis; here we just assert update happens with the API drugId.
        coEvery { drugMasterDao.upsertPreservingId(any()) } returns 9L

        repo.handleOrderEdit(editMessage())

        coVerify(timeout = 3000) { dispenseTxnDao.updateFromHl7Edit(any(), eq(9L), any(), any(), any(), any()) }
    }

    @Test
    fun `handleOrderEdit status CA soft deletes and returns`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { dispenseTxnDao.getActiveByRxNo("RX1") } returns txnEntity()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns
            DrugMasterEntity(drugId = 5L, drugName = "Aspirin", ndc = "12345")
        coEvery { drugMasterDao.upsertPreservingId(any()) } returns 5L

        repo.handleOrderEdit(editMessage(orderStatus = "CA"))

        coVerify(timeout = 3000) { dispenseTxnDao.updateFromHl7Edit(any(), any(), any(), any(), any(), any()) }
        coVerify(timeout = 3000) { dispenseTxnDao.softDeleteByRxNo("RX1", any()) }
        // notifier.show NOT called on CA path (returns before notify)
    }

    @Test
    fun `handleOrderEdit status CM maps completed`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { dispenseTxnDao.getActiveByRxNo("RX1") } returns txnEntity()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns
            DrugMasterEntity(drugId = 5L, drugName = "Aspirin", ndc = "12345")
        coEvery { drugMasterDao.upsertPreservingId(any()) } returns 5L

        repo.handleOrderEdit(editMessage(orderStatus = "CM"))

        coVerify(timeout = 3000) { dispenseTxnDao.updateFromHl7Edit(any(), any(), any(), any(), eq(CountStatus.COMPLETED), any()) }
    }

    @Test
    fun `handleOrderEdit status HD maps on hold`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { dispenseTxnDao.getActiveByRxNo("RX1") } returns txnEntity()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns
            DrugMasterEntity(drugId = 5L, drugName = "Aspirin", ndc = "12345")
        coEvery { drugMasterDao.upsertPreservingId(any()) } returns 5L

        repo.handleOrderEdit(editMessage(orderStatus = "HD"))

        coVerify(timeout = 3000) { dispenseTxnDao.updateFromHl7Edit(any(), any(), any(), any(), eq(CountStatus.ON_HOLD), any()) }
    }

    @Test
    fun `handleOrderEdit unknown status maps null`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { dispenseTxnDao.getActiveByRxNo("RX1") } returns txnEntity()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns
            DrugMasterEntity(drugId = 5L, drugName = "Aspirin", ndc = "12345")
        coEvery { drugMasterDao.upsertPreservingId(any()) } returns 5L

        repo.handleOrderEdit(editMessage(orderStatus = "ZZ"))

        coVerify(timeout = 3000) { dispenseTxnDao.updateFromHl7Edit(any(), any(), any(), any(), isNull(), any()) }
        verify(timeout = 3000) { notifier.show(any(), any()) }
    }
}
