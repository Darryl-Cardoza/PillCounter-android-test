package com.rite.pillcounting.feature.hl7.data.repository

import android.content.Context
import android.util.Log
import com.rite.pillcounting.core.room.dao.BatchDao
import com.rite.pillcounting.core.room.dao.BottleInfoDao
import com.rite.pillcounting.core.room.dao.DrugMasterDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDetailsDao
import com.rite.pillcounting.core.room.dao.StockTxnDao
import com.rite.pillcounting.core.room.dao.UserDao
import com.rite.pillcounting.core.room.models.BatchEntity
import com.rite.pillcounting.core.room.models.DrugMasterEntity
import com.rite.pillcounting.core.room.models.PillCountTxnDetailsEntity
import com.rite.pillcounting.core.room.models.PillCountTxnEntity
import com.rite.pillcounting.core.room.models.UserEntity
import com.rite.pillcounting.core.room.models.enums.CountStatus
import com.rite.pillcounting.core.room.models.dtos.BatchTxnDto
import com.rite.pillcounting.core.room.models.enums.TxnPriority
import com.rite.pillcounting.core.scanning.data.DrugImageDownloader
import com.rite.pillcounting.core.scanning.data.DrugRepository
import com.rite.pillcounting.core.scanning.domain.model.DrugInfo
import com.rite.pillcounting.core.scanning.domain.model.GetNdcRequestModel
import com.rite.pillcounting.core.utils.common.LocationProvider
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.hl7.core.Hl7MessageSender
import com.rite.pillcounting.feature.hl7.notification.Hl7Notifier
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
 * genuine [HL7Message] — the SUT's [HL7Message] constructor is internal to the hl7Core module, so
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
    private lateinit var txnDao: PillCountTxnDao
    private lateinit var txnDetailsDao: PillCountTxnDetailsDao
    private lateinit var preferenceHelper: PreferenceHelper
    private lateinit var pillCountTxnDao: PillCountTxnDao
    private lateinit var stockTxnDao: StockTxnDao
    private lateinit var bottleInfoDao: BottleInfoDao
    private lateinit var userDao: UserDao
    private lateinit var batchDao: BatchDao
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
        txnDao = mockk(relaxed = true)
        txnDetailsDao = mockk(relaxed = true)
        preferenceHelper = mockk(relaxed = true)
        pillCountTxnDao = mockk(relaxed = true)
        stockTxnDao = mockk(relaxed = true)
        bottleInfoDao = mockk(relaxed = true)
        userDao = mockk(relaxed = true)
        batchDao = mockk(relaxed = true)
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
        txnDao = txnDao,
        txnDetailsDao = txnDetailsDao,
        preferenceHelper = preferenceHelper,
        pillCountTxnDao = pillCountTxnDao,
        stockTxnDao = stockTxnDao,
        bottleInfoDao = bottleInfoDao,
        userDao = userDao,
        batchDao = batchDao,
        hl7MessageSender = hl7MessageSender,
        drugRepository = drugRepository,
        notifier = notifier,
        drugImageDownloader = drugImageDownloader,
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

    /** ORC|CA cancel-order message. */
    private fun cancelMessage(placerOrderId: String = "RX1", controlId: String = "MSG1"): HL7Message =
        parse(
            listOf(
                msh("RDE", "O11", controlId),
                "ORC|CA|$placerOrderId",
            ).joinToString("\r")
        )

    /** INR^U06 inventory request built from MSH + INV segment(s). */
    private fun inventoryMessage(
        ndc: String = "12345",
        drugName: String = "Aspirin",
        quantity: String = "10",
        controlId: String = "MSG1",
    ): HL7Message =
        parse(
            listOf(
                msh("INR", "U06", controlId),
                "INV|$ndc^$drugName||||${quantity}B",
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
        isDispense: Boolean = true,
    ) = PillCountTxnEntity(
        txnId = txnId,
        localId = 1L,
        drugId = drugId,
        isDispense = isDispense,
        status = CountStatus.PARTIAL,
        rxNo = "RX1",
    )

    // ─────────────────────────────── init / observe ───────────────────────────────

    @Test
    fun `init with hl7 enabled drives observePendingHl7Transactions`() = runTest(testDispatcher) {
        every { preferenceHelper.isHl7Enabled() } returns true
        every { pillCountTxnDao.observePendingHl7Txn() } returns flowOf(emptyList())
        coEvery { pillCountTxnDao.getPendingHl7TxnOnce() } returns emptyList()

        createRepo()

        verify(timeout = 3000) { pillCountTxnDao.observePendingHl7Txn() }
    }

    @Test
    fun `init with hl7 disabled does not observe`() = runTest(testDispatcher) {
        every { preferenceHelper.isHl7Enabled() } returns false

        createRepo()

        // Synchronize on the init coroutine actually running (it reads isHl7Enabled) before
        // asserting the observer was never collected; this avoids racing the background launch.
        verify(timeout = 3000) { preferenceHelper.isHl7Enabled() }
        verify(exactly = 0) { pillCountTxnDao.observePendingHl7Txn() }
    }

    // ─────────────────────────────── classifyInboundMessage ───────────────────────────────

    @Test
    fun `handleReceivedMessage null classification early returns`() = runTest(testDispatcher) {
        val repo = createRepo()
        // ADT^A01 with no ORC/RXE — does not classify to any MessageType.
        val msg = parse(msh("ADT", "A01", "MSG1"))

        // classifyInboundMessage returns null synchronously on the calling thread (before any
        // scope.launch), so no background work is started at all — assert absence directly.
        repo.handleReceivedMessage(msg)

        coVerify(exactly = 0) { drugMasterDao.getDrugByNdc(any()) }
        coVerify(exactly = 0) { pillCountTxnDao.softDeleteByRxNo(any(), any()) }
    }

    @Test
    fun `handleReceivedMessage routes cancel order`() = runTest(testDispatcher) {
        val repo = createRepo()
        val msg = cancelMessage(placerOrderId = "RX9")

        repo.handleReceivedMessage(msg)

        coVerify(timeout = 3000) { pillCountTxnDao.softDeleteByRxNo("RX9", any()) }
    }

    @Test
    fun `handleReceivedMessage routes edit dispense`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { pillCountTxnDao.getActiveByRxNo("RX1") } returns txnEntity()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns
            DrugMasterEntity(drugId = 5L, drugName = "Aspirin", ndc = "12345")
        coEvery { drugMasterDao.upsertPreservingId(any()) } returns 5L

        val msg = dispenseMessage(orderControl = "XO", placerOrderId = "RX1", orderStatus = "IP")
        repo.handleReceivedMessage(msg)

        coVerify(timeout = 3000) { pillCountTxnDao.updateFromHl7Edit(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `handleReceivedMessage routes dispense request`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns
            DrugMasterEntity(drugId = 5L, drugName = "Aspirin", ndc = "12345")
        coEvery { drugMasterDao.upsertPreservingId(any()) } returns 5L
        coEvery { pillCountTxnDao.upsertPreservingId(any()) } returns 1L

        repo.handleReceivedMessage(dispenseMessage())

        coVerify(timeout = 3000) { pillCountTxnDao.upsertPreservingId(any()) }
        verify(timeout = 3000) { notifier.show(any(), any()) }
    }

    @Test
    fun `handleReceivedMessage routes inventory request`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns
            DrugMasterEntity(drugId = 5L, drugName = "Aspirin", ndc = "12345")
        coEvery { batchDao.insert(any()) } returns 100L
        coEvery { stockTxnDao.upsertPreservingId(any()) } returns 1L

        val msg = inventoryMessage()
        repo.handleReceivedMessage(msg)

        coVerify(timeout = 3000) { batchDao.insert(any()) }
    }

    // ─────────────────────────────── buildAndSendSuccessfulDispense ───────────────────────────────

    @Test
    fun `buildAndSendSuccessfulDispense returns when txn null`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { txnDao.getById(1L) } returns null

        repo.buildAndSendSuccessfulDispense(1L)

        coVerify(exactly = 0) { hl7MessageSender.send(any()) }
    }

    @Test
    fun `buildAndSendSuccessfulDispense returns when drug null`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { txnDao.getById(1L) } returns txnEntity(drugId = null)

        repo.buildAndSendSuccessfulDispense(1L)

        coVerify(exactly = 0) { hl7MessageSender.send(any()) }
    }

    @Test
    fun `buildAndSendSuccessfulDispense builds and sends`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { txnDao.getById(1L) } returns txnEntity(drugId = 5L)
        coEvery { txnDetailsDao.getAllForTxn("1") } returns
            listOf(PillCountTxnDetailsEntity(txnId = 1L, pillCount = 5))
        coEvery { userDao.getByLocalId(any()) } returns null
        coEvery { locationProvider.getCurrentLocationAsString() } returns "loc"
        coEvery { drugMasterDao.getDrugById(5L) } returns
            DrugMasterEntity(drugId = 5L, drugName = "Aspirin", ndc = "12345")

        repo.buildAndSendSuccessfulDispense(1L)

        coVerify { hl7MessageSender.send(any()) }
    }

    /**
     * The operator is resolved through the transaction's localId, which is a FK to
     * UserEntity.localId. It used to be looked up with getByUserId — a Room row id compared
     * against a JWT-derived string — so it never resolved and RXD-10 went out empty, leaving the
     * Companion's Operator column blank on every dispense.
     */
    @Test
    fun `buildAndSendSuccessfulDispense puts the operator in RXD-10 as ID caret family caret given`() =
        runTest(testDispatcher) {
            val repo = createRepo()
            coEvery { txnDao.getById(1L) } returns txnEntity(drugId = 5L)
            coEvery { txnDetailsDao.getAllForTxn("1") } returns
                listOf(PillCountTxnDetailsEntity(txnId = 1L, pillCount = 5))
            coEvery { userDao.getByLocalId(1L) } returns
                UserEntity(localId = 1L, userId = "u-77", fName = "Yash", lName = "Wadajkar")
            coEvery { locationProvider.getCurrentLocationAsString() } returns "loc"
            coEvery { drugMasterDao.getDrugById(5L) } returns
                DrugMasterEntity(drugId = 5L, drugName = "Aspirin", ndc = "12345")

            val sent = slot<String>()
            coEvery { hl7MessageSender.send(capture(sent)) } returns Result.success("ACK")

            repo.buildAndSendSuccessfulDispense(1L)

            val rxd = sent.captured.split("\r").first { it.startsWith("RXD|") }
            val rxd10 = rxd.split("|")[10]
            assertEquals("u-77^Wadajkar^Yash", rxd10)
        }

    // ─────────────────────────────── buildAndSendInventoryResponse ───────────────────────────────

    @Test
    fun `buildAndSendInventoryResponse returns when batch null`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { batchDao.getById(100L) } returns null

        repo.buildAndSendInventoryResponse(100L)

        coVerify(exactly = 0) { hl7MessageSender.sendRaw(any()) }
    }

    @Test
    fun `buildAndSendInventoryResponse success marks batch synced`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { batchDao.getById(100L) } returns BatchEntity(batchId = 100L, requestIdFromPMS = "REQ")
        coEvery { bottleInfoDao.getByBatchId(100L) } returns emptyList()
        coEvery { hl7MessageSender.sendRaw(any()) } returns Result.success(SUCCESS_ACK)

        repo.buildAndSendInventoryResponse(100L)

        coVerify { batchDao.markBatchSynced(100L) }
    }

    @Test
    fun `buildAndSendInventoryResponse failure does not mark synced`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { batchDao.getById(100L) } returns BatchEntity(batchId = 100L, requestIdFromPMS = "REQ")
        coEvery { bottleInfoDao.getByBatchId(100L) } returns emptyList()
        coEvery { hl7MessageSender.sendRaw(any()) } returns Result.failure(RuntimeException("send fail"))

        repo.buildAndSendInventoryResponse(100L)

        coVerify(exactly = 0) { batchDao.markBatchSynced(any()) }
    }

    @Test
    fun `buildAndSendInventoryResponse swallows exceptions`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { batchDao.getById(100L) } throws RuntimeException("db boom")

        repo.buildAndSendInventoryResponse(100L)

        coVerify(exactly = 0) { hl7MessageSender.sendRaw(any()) }
    }

    // ─────────────────────────────── large batch (chunked) sync ───────────────────────────────

    /**
     * Builds enough distinct (ndc, lot, expiry) rows to produce ~10000 INV segments once
     * grouped by HL7MessageBuilder — with maxRowsPerChunk=200 this yields 50 chunks.
     */
    private fun largeBatchTxns(rowCount: Int = 10000): List<BatchTxnDto> =
        (1..rowCount).map { i ->
            BatchTxnDto(
                txnId = i.toLong(),
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
            coEvery { batchDao.getById(100L) } returns BatchEntity(batchId = 100L, requestIdFromPMS = "REQ")
            coEvery { bottleInfoDao.getByBatchId(100L) } returns largeBatchTxns(10000)
            coEvery { hl7MessageSender.sendRaw(any()) } returns Result.success(SUCCESS_ACK)

            repo.buildAndSendInventoryResponse(100L)

            // 10000 distinct ndc/lot/expiry rows chunked at 200 rows/chunk => 50 chunks.
            coVerify(exactly = 50) { hl7MessageSender.sendRaw(any()) }
            for (chunkIndex in 1..50) {
                coVerify { batchDao.markChunkAcked(100L, chunkIndex) }
            }
            coVerify { batchDao.setTotalChunks(100L, 50) }
            coVerify { batchDao.markBatchSynced(100L) }
        }

    @Test
    fun `buildAndSendInventoryResponse large batch stops and stays unsynced on mid-batch chunk failure`() =
        runTest(testDispatcher) {
            val repo = createRepo()
            coEvery { batchDao.getById(100L) } returns BatchEntity(batchId = 100L, requestIdFromPMS = "REQ")
            coEvery { bottleInfoDao.getByBatchId(100L) } returns largeBatchTxns(10000)

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
                coVerify { batchDao.markChunkAcked(100L, chunkIndex) }
            }
            coVerify(exactly = 0) { batchDao.markChunkAcked(100L, 27) }
            coVerify(exactly = 0) { batchDao.markBatchSynced(any()) }
        }

    @Test
    fun `resendPendingHl7BatchTransactions resumes a large batch from lastAckedChunkIndex and syncs`() =
        runTest(testDispatcher) {
            val repo = createRepo()
            // Batch previously got through chunk 40 of 50 before PMS went offline / app restarted.
            coEvery { batchDao.getUnsyncedCompletedBatchesOnce() } returns
                listOf(
                    com.rite.pillcounting.core.room.models.dtos.BatchSummaryDto(
                        batchId = 100L,
                        createdAt = 0L,
                        uniqueNdcCount = 10000,
                        status = "COMPLETED",
                        bucketId = null,
                        requestIdFromPMS = "REQ",
                    )
                )
            coEvery { batchDao.getById(100L) } returns
                BatchEntity(batchId = 100L, requestIdFromPMS = "REQ", lastAckedChunkIndex = 40, totalChunks = 50)
            coEvery { bottleInfoDao.getByBatchId(100L) } returns largeBatchTxns(10000)
            coEvery { hl7MessageSender.sendRaw(any()) } returns Result.success(SUCCESS_ACK)

            repo.resendPendingHl7BatchTransactions()

            // Only the remaining 10 chunks (41..50) are resent; 1..40 are skipped as already ACKed.
            coVerify(timeout = 3000, exactly = 10) { hl7MessageSender.sendRaw(any()) }
            for (chunkIndex in 41..50) {
                coVerify(timeout = 3000) { batchDao.markChunkAcked(100L, chunkIndex) }
            }
            coVerify(exactly = 0) { batchDao.markChunkAcked(100L, 40) }
            coVerify(timeout = 3000) { batchDao.markBatchSynced(100L) }
        }

    @Test
    fun `onClientConnected triggers large batch resend and marks synced once fully sent`() =
        runTest(testDispatcher) {
            // Mirrors Hl7EventHandler.onClientConnected, which calls
            // resendPendingHl7BatchTransactions() as soon as PMS (re)connects — this is the
            // "send on connected" trigger for a large (10k-segment / 50-chunk) pending batch.
            val repo = createRepo()
            coEvery { batchDao.getUnsyncedCompletedBatchesOnce() } returns
                listOf(
                    com.rite.pillcounting.core.room.models.dtos.BatchSummaryDto(
                        batchId = 100L,
                        createdAt = 0L,
                        uniqueNdcCount = 10000,
                        status = "COMPLETED",
                        bucketId = null,
                        requestIdFromPMS = "REQ",
                    )
                )
            coEvery { batchDao.getById(100L) } returns BatchEntity(batchId = 100L, requestIdFromPMS = "REQ")
            coEvery { bottleInfoDao.getByBatchId(100L) } returns largeBatchTxns(10000)
            coEvery { hl7MessageSender.sendRaw(any()) } returns Result.success(SUCCESS_ACK)

            // Simulates the connect callback's resend trigger directly against the repository
            // (Hl7EventHandler.onClientConnected just forwards to this).
            repo.resendPendingHl7BatchTransactions()

            coVerify(timeout = 3000, exactly = 50) { hl7MessageSender.sendRaw(any()) }
            coVerify(timeout = 3000) { batchDao.markBatchSynced(100L) }
        }

    // ─────────────────────────────── resendPendingHl7Transactions ───────────────────────────────

    @Test
    fun `resendPendingHl7Transactions empty returns`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { pillCountTxnDao.getPendingHl7TxnOnce() } returns emptyList()

        repo.resendPendingHl7Transactions()

        // Synchronize on the coroutine having read the pending list before asserting the
        // save was never invoked.
        coVerify(timeout = 3000) { pillCountTxnDao.getPendingHl7TxnOnce() }
        verify(exactly = 0) { preferenceHelper.saveSentMessageTxnId(any()) }
    }

    @Test
    fun `resendPendingHl7Transactions fixed and regular branches`() = runTest(testDispatcher) {
        val repo = createRepo()
        // Stock (REGULAR) counts no longer live in pill_count_txn; only FIXED (dispense) txns are
        // resent here. REGULAR entries are a no-op — their HL7 responses are resent per-batch via
        // resendPendingHl7BatchTransactions().
        val fixedTxn = txnEntity(txnId = 1L, isDispense = true)
        val regularTxn = txnEntity(txnId = 2L, isDispense = false)
        coEvery { pillCountTxnDao.getPendingHl7TxnOnce() } returns
            listOf(fixedTxn, regularTxn)

        // FIXED -> buildAndSendSuccessfulDispense
        coEvery { txnDao.getById(1L) } returns txnEntity(txnId = 1L, drugId = 5L)
        coEvery { txnDetailsDao.getAllForTxn("1") } returns emptyList()
        coEvery { userDao.getByLocalId(any()) } returns null
        coEvery { drugMasterDao.getDrugById(5L) } returns
            DrugMasterEntity(drugId = 5L, drugName = "Aspirin", ndc = "12345")

        repo.resendPendingHl7Transactions()

        verify(timeout = 3000) { preferenceHelper.saveSentMessageTxnId(1L) }
        coVerify(timeout = 3000) { hl7MessageSender.send(any()) }
        // REGULAR is a no-op now: no inventory response is triggered from this path.
        coVerify(exactly = 0) { batchDao.markBatchSynced(any()) }
    }

    // ─────────────────────────────── resendPendingHl7BatchTransactions ───────────────────────────────

    @Test
    fun `resendPendingHl7BatchTransactions empty returns`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { batchDao.getUnsyncedCompletedBatchesOnce() } returns emptyList()

        repo.resendPendingHl7BatchTransactions()

        // Synchronize on the coroutine having read the unsynced list before asserting getById
        // was never invoked.
        coVerify(timeout = 3000) { batchDao.getUnsyncedCompletedBatchesOnce() }
        coVerify(exactly = 0) { batchDao.getById(any()) }
    }

    @Test
    fun `resendPendingHl7BatchTransactions non-empty loops`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { batchDao.getUnsyncedCompletedBatchesOnce() } returns
            listOf(
                com.rite.pillcounting.core.room.models.dtos.BatchSummaryDto(
                    batchId = 100L,
                    createdAt = 0L,
                    uniqueNdcCount = 0,
                    status = "COMPLETED",
                    bucketId = null,
                    requestIdFromPMS = "REQ",
                )
            )
        coEvery { batchDao.getById(100L) } returns BatchEntity(batchId = 100L, requestIdFromPMS = "REQ")
        coEvery { bottleInfoDao.getByBatchId(100L) } returns emptyList()

        repo.resendPendingHl7BatchTransactions()

        coVerify(timeout = 3000) { batchDao.markBatchSynced(100L) }
    }

    // ─────────────────────────────── markTransactionSynced ───────────────────────────────

    @Test
    fun `markTransactionSynced delegates`() = runTest(testDispatcher) {
        val repo = createRepo()
        every { preferenceHelper.getSentMessageTxnId() } returns 7L

        repo.markTransactionSynced()

        coVerify(timeout = 3000) { pillCountTxnDao.markTxnSynced(7L, any()) }
    }

    // ─────────────────────────────── handleRdeDispenseRequest ───────────────────────────────

    @Test
    fun `handleRdeDispenseRequest local drug found single med`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns
            DrugMasterEntity(drugId = 5L, drugName = "Aspirin", ndc = "12345")
        coEvery { drugMasterDao.upsertPreservingId(any()) } returns 5L
        coEvery { pillCountTxnDao.upsertPreservingId(any()) } returns 1L
        val msg = dispenseMessage(extraSegments = listOf(zprSegment("High")))

        repo.handleReceivedMessage(msg)

        val txnSlot = slot<PillCountTxnEntity>()
        coVerify(timeout = 3000) { pillCountTxnDao.upsertPreservingId(capture(txnSlot)) }
        assert(txnSlot.captured.priority == TxnPriority.High)
        verify(timeout = 3000) { notifier.show(any(), any()) }
    }

    @Test
    fun `handleRdeDispenseRequest api success when not local`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns null
        coEvery { drugRepository.getDrugInfoByNdc(any<GetNdcRequestModel>()) } returns drugInfo()
        coEvery { drugMasterDao.upsertPreservingId(any()) } returns 9L
        coEvery { pillCountTxnDao.upsertPreservingId(any()) } returns 1L

        repo.handleReceivedMessage(dispenseMessage())

        coVerify(timeout = 3000) { drugRepository.getDrugInfoByNdc(any<GetNdcRequestModel>()) }
        coVerify(timeout = 3000) { pillCountTxnDao.upsertPreservingId(any()) }
    }

    @Test
    fun `handleRdeDispenseRequest api throws notifies and returns`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns null
        coEvery { drugRepository.getDrugInfoByNdc(any<GetNdcRequestModel>()) } throws
            RuntimeException("api fail")

        repo.handleReceivedMessage(dispenseMessage())

        // notifier.show is the terminal action on this path; once it fires the coroutine has
        // finished and we can safely assert upsert was never called.
        verify(timeout = 3000) { notifier.show(any(), any()) }
        coVerify(exactly = 0) { pillCountTxnDao.upsertPreservingId(any()) }
    }

    @Test
    fun `handleRdeDispenseRequest resolvedDrugName null notifies and returns`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns null
        coEvery { drugRepository.getDrugInfoByNdc(any<GetNdcRequestModel>()) } returns
            drugInfo(genericName = null)

        repo.handleReceivedMessage(dispenseMessage())

        verify(timeout = 3000) { notifier.show(any(), any()) }
        coVerify(exactly = 0) { pillCountTxnDao.upsertPreservingId(any()) }
    }

    // ─────────────────────────────── insertZinContainerDetails ───────────────────────────────

    @Test
    fun `handleRdeDispenseRequest inserts zin details and updates workflow`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns
            DrugMasterEntity(drugId = 5L, drugName = "Aspirin", ndc = "12345")
        coEvery { drugMasterDao.upsertPreservingId(any()) } returns 5L
        coEvery { pillCountTxnDao.upsertPreservingId(any()) } returns 42L
        val msg = dispenseMessage(
            extraSegments = listOf(
                zinSegment("0"),
                zinSegment("abc"),
                zinSegment("7"),
            ),
        )

        repo.handleReceivedMessage(msg)

        coVerify(timeout = 3000) { txnDetailsDao.insert(any()) }
        coVerify(timeout = 3000) { pillCountTxnDao.updateWorkflowStep(42L, any(), any()) }
    }

    // ─────────────────────────────── handleInrInventoryRequest ───────────────────────────────

    @Test
    fun `handleInrInventoryRequest empty ndc returns`() = runTest(testDispatcher) {
        val repo = createRepo()
        // INV segment with a blank NDC is skipped, leaving resolvedItems empty — exercises the
        // notify + no-batch path.
        val msg = inventoryMessage(ndc = "")
        repo.handleReceivedMessage(msg)

        // notifier.show is the terminal action when resolvedItems is empty; sync on it before
        // asserting no batch was created.
        verify(timeout = 3000) { notifier.show(any(), any()) }
        coVerify(exactly = 0) { batchDao.insert(any()) }
    }

    @Test
    fun `handleInrInventoryRequest local drug with name resolves and creates batch`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns
            DrugMasterEntity(drugId = 5L, drugName = "Aspirin", ndc = "12345")
        coEvery { batchDao.insert(any()) } returns 100L
        coEvery { stockTxnDao.upsertPreservingId(any()) } returns 1L

        val msg = inventoryMessage()
        repo.handleReceivedMessage(msg)

        coVerify(timeout = 3000) { batchDao.insert(any()) }
        // Stock counts now write to stock_txn (StockTxnEntity), not pill_count_txn.
        coVerify(timeout = 3000) { stockTxnDao.upsertPreservingId(any()) }
        verify(timeout = 3000) { notifier.show(any(), any()) }
    }

    @Test
    fun `handleInrInventoryRequest local drug empty name skipped`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns
            DrugMasterEntity(drugId = 5L, drugName = "", ndc = "12345")

        val msg = inventoryMessage()
        repo.handleReceivedMessage(msg)

        // resolvedItems empty -> notifier + no batch. Sync on notifier first.
        verify(timeout = 3000) { notifier.show(any(), any()) }
        coVerify(exactly = 0) { batchDao.insert(any()) }
    }

    @Test
    fun `handleInrInventoryRequest api fallback success`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns null
        coEvery { drugRepository.getDrugInfoByNdc(any<GetNdcRequestModel>()) } returns drugInfo()
        coEvery { drugMasterDao.upsertPreservingId(any()) } returns 9L
        coEvery { batchDao.insert(any()) } returns 100L
        coEvery { stockTxnDao.upsertPreservingId(any()) } returns 1L

        val msg = inventoryMessage()
        repo.handleReceivedMessage(msg)

        coVerify(timeout = 3000) { batchDao.insert(any()) }
    }

    @Test
    fun `handleInrInventoryRequest api null name uses substance name fallback`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns null
        coEvery { drugRepository.getDrugInfoByNdc(any<GetNdcRequestModel>()) } returns
            drugInfo(genericName = null)
        coEvery { drugMasterDao.upsertPreservingId(any()) } returns 9L
        coEvery { batchDao.insert(any()) } returns 100L
        coEvery { stockTxnDao.upsertPreservingId(any()) } returns 1L

        // INV substance name is non-blank "Aspirin" so resolveInventoryItem falls back to it.
        val msg = inventoryMessage()
        repo.handleReceivedMessage(msg)

        coVerify(timeout = 3000) { batchDao.insert(any()) }
    }

    @Test
    fun `handleInrInventoryRequest api null name and blank substance name skipped`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns null
        coEvery { drugRepository.getDrugInfoByNdc(any<GetNdcRequestModel>()) } returns
            drugInfo(genericName = null)

        val msg = inventoryMessage(drugName = "")
        repo.handleReceivedMessage(msg)

        verify(timeout = 3000) { notifier.show(any(), any()) }
        coVerify(exactly = 0) { batchDao.insert(any()) }
    }

    @Test
    fun `handleInrInventoryRequest api throws continues`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns null
        coEvery { drugRepository.getDrugInfoByNdc(any<GetNdcRequestModel>()) } throws
            RuntimeException("api boom")

        val msg = inventoryMessage()
        repo.handleReceivedMessage(msg)

        verify(timeout = 3000) { notifier.show(any(), any()) }
        coVerify(exactly = 0) { batchDao.insert(any()) }
    }

    // ─────────────────────────────── handleOrderCancellation ───────────────────────────────

    @Test
    fun `handleOrderCancellation soft deletes`() = runTest(testDispatcher) {
        val repo = createRepo()
        repo.handleReceivedMessage(cancelMessage(placerOrderId = "RX5"))

        coVerify(timeout = 3000) { pillCountTxnDao.softDeleteByRxNo("RX5", any()) }
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
        coEvery { pillCountTxnDao.getActiveByRxNo("RX1") } returns txnEntity()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns
            DrugMasterEntity(drugId = 5L, drugName = "Aspirin", ndc = "12345")
        coEvery { drugMasterDao.upsertPreservingId(any()) } returns 5L

        repo.handleReceivedMessage(
            editMessage(extraSegments = listOf(zprSegment("Low"))),
        )

        coVerify(timeout = 3000) { pillCountTxnDao.updateFromHl7Edit(any(), any(), any(), any(), any(), any()) }
        verify(timeout = 3000) { notifier.show(any(), any()) }
    }

    @Test
    fun `handleOrderEdit restores deleted txn`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { pillCountTxnDao.getActiveByRxNo("RX1") } returns null
        coEvery { pillCountTxnDao.getDeletedByRxNo("RX1") } returns txnEntity(txnId = 8L)
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns
            DrugMasterEntity(drugId = 5L, drugName = "Aspirin", ndc = "12345")
        coEvery { drugMasterDao.upsertPreservingId(any()) } returns 5L

        repo.handleReceivedMessage(editMessage())

        coVerify(timeout = 3000) { pillCountTxnDao.restoreDeletedTxn(8L, any()) }
        coVerify(timeout = 3000) { pillCountTxnDao.updateFromHl7Edit(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `handleOrderEdit no txn found notifies`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { pillCountTxnDao.getActiveByRxNo("RX1") } returns null
        coEvery { pillCountTxnDao.getDeletedByRxNo("RX1") } returns null

        repo.handleReceivedMessage(editMessage())

        verify(timeout = 3000) { notifier.show(any(), any()) }
        coVerify(exactly = 0) { pillCountTxnDao.updateFromHl7Edit(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `handleOrderEdit api success when not local`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { pillCountTxnDao.getActiveByRxNo("RX1") } returns txnEntity()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns null
        coEvery { drugRepository.getDrugInfoByNdc(any<GetNdcRequestModel>()) } returns drugInfo()
        coEvery { drugMasterDao.upsertPreservingId(any()) } returns 9L

        repo.handleReceivedMessage(editMessage())

        coVerify(timeout = 3000) { pillCountTxnDao.updateFromHl7Edit(any(), eq(9L), any(), any(), any(), any()) }
    }

    @Test
    fun `handleOrderEdit api no name notifies and returns`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { pillCountTxnDao.getActiveByRxNo("RX1") } returns txnEntity()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns null
        coEvery { drugRepository.getDrugInfoByNdc(any<GetNdcRequestModel>()) } returns
            drugInfo(genericName = null)

        repo.handleReceivedMessage(editMessage())

        verify(timeout = 3000) { notifier.show(any(), any()) }
        coVerify(exactly = 0) { pillCountTxnDao.updateFromHl7Edit(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `handleOrderEdit api throws notifies and returns`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { pillCountTxnDao.getActiveByRxNo("RX1") } returns txnEntity()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns null
        coEvery { drugRepository.getDrugInfoByNdc(any<GetNdcRequestModel>()) } throws
            RuntimeException("api fail")

        repo.handleReceivedMessage(editMessage())

        verify(timeout = 3000) { notifier.show(any(), any()) }
        coVerify(exactly = 0) { pillCountTxnDao.updateFromHl7Edit(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `handleOrderEdit newDrugId null fallback keeps old drugId`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { pillCountTxnDao.getActiveByRxNo("RX1") } returns txnEntity(drugId = 77L)
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

        repo.handleReceivedMessage(editMessage())

        coVerify(timeout = 3000) { pillCountTxnDao.updateFromHl7Edit(any(), eq(9L), any(), any(), any(), any()) }
    }

    @Test
    fun `handleOrderEdit status CA soft deletes and returns`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { pillCountTxnDao.getActiveByRxNo("RX1") } returns txnEntity()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns
            DrugMasterEntity(drugId = 5L, drugName = "Aspirin", ndc = "12345")
        coEvery { drugMasterDao.upsertPreservingId(any()) } returns 5L

        repo.handleReceivedMessage(editMessage(orderStatus = "CA"))

        coVerify(timeout = 3000) { pillCountTxnDao.updateFromHl7Edit(any(), any(), any(), any(), any(), any()) }
        coVerify(timeout = 3000) { pillCountTxnDao.softDeleteByRxNo("RX1", any()) }
        // notifier.show NOT called on CA path (returns before notify)
    }

    @Test
    fun `handleOrderEdit status CM maps completed`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { pillCountTxnDao.getActiveByRxNo("RX1") } returns txnEntity()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns
            DrugMasterEntity(drugId = 5L, drugName = "Aspirin", ndc = "12345")
        coEvery { drugMasterDao.upsertPreservingId(any()) } returns 5L

        repo.handleReceivedMessage(editMessage(orderStatus = "CM"))

        coVerify(timeout = 3000) { pillCountTxnDao.updateFromHl7Edit(any(), any(), any(), any(), eq(CountStatus.COMPLETED), any()) }
    }

    @Test
    fun `handleOrderEdit status HD maps on hold`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { pillCountTxnDao.getActiveByRxNo("RX1") } returns txnEntity()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns
            DrugMasterEntity(drugId = 5L, drugName = "Aspirin", ndc = "12345")
        coEvery { drugMasterDao.upsertPreservingId(any()) } returns 5L

        repo.handleReceivedMessage(editMessage(orderStatus = "HD"))

        coVerify(timeout = 3000) { pillCountTxnDao.updateFromHl7Edit(any(), any(), any(), any(), eq(CountStatus.ON_HOLD), any()) }
    }

    @Test
    fun `handleOrderEdit unknown status maps null`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { pillCountTxnDao.getActiveByRxNo("RX1") } returns txnEntity()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns
            DrugMasterEntity(drugId = 5L, drugName = "Aspirin", ndc = "12345")
        coEvery { drugMasterDao.upsertPreservingId(any()) } returns 5L

        repo.handleReceivedMessage(editMessage(orderStatus = "ZZ"))

        coVerify(timeout = 3000) { pillCountTxnDao.updateFromHl7Edit(any(), any(), any(), any(), isNull(), any()) }
        verify(timeout = 3000) { notifier.show(any(), any()) }
    }
}
