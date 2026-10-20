package com.dispensesure.retail.feature.hl7.data.repository

import android.content.Context
import com.dispensesure.retail.core.faceAuth.data.OperatorNameProvider
import com.dispensesure.retail.core.region.Region
import com.dispensesure.retail.core.region.RegionProvider
import com.dispensesure.retail.core.room.dao.DrugMasterDao
import com.dispensesure.retail.core.room.dao.StockBatchDao
import com.dispensesure.retail.core.room.dao.StockBottleInfoDao
import com.dispensesure.retail.core.room.dao.StockTxnDao
import com.dispensesure.retail.core.room.models.DrugMasterEntity
import com.dispensesure.retail.core.room.models.StockBatchEntity
import com.dispensesure.retail.core.room.models.dtos.StockBottleInfoDto
import com.dispensesure.retail.core.scanning.data.DrugImageDownloader
import com.dispensesure.retail.core.scanning.data.DrugRepository
import com.dispensesure.retail.core.scanning.data.DrugResolver
import com.dispensesure.retail.core.scanning.domain.model.GetNdcRequestModel
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.rite.hl7.model.HL7Message
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The SUT creates its own `CoroutineScope(Dispatchers.IO)` at construction and launches its
 * fire-and-forget handlers on it. We deliberately do NOT statically mock [Dispatchers] here:
 * doing so causes MockK's own verification machinery (which internally touches
 * `Dispatchers.getDefault()`) to be recorded into the verify scope, producing intermittent
 * "should not be called" failures and making the whole class flaky.
 *
 * Instead the SUT's scope runs on the real Dispatchers.IO background threads. Two kinds of
 * methods exist:
 *  - `suspend` methods called directly inside runTest (sendInventory) run inline and are
 *    verified with a plain `coVerify { }` immediately after the call.
 *  - methods that hand work to a coroutine on the SUT's scope (resendPendingBatches)
 *    run asynchronously on background threads; they are verified with MockK's
 *    polling form `coVerify(timeout = 3000) { }` / `verify(timeout = 3000) { }`. Absence
 *    ("not called") is only asserted AFTER synchronizing on a positive signal that proves the
 *    coroutine reached the relevant point.
 *
 * Inbound HL7 messages are built by parsing real wire-format HL7 text via [HL7Parser] into a
 * genuine [HL7Message] — the SUT's [HL7Message] constructor is internal to the HL7 library AAR, so
 * tests cannot construct one directly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StockHl7RepositoryTest {

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

    @Before
    fun setup() {
        stubAndroidLog()

        // setMain is kept so suspend calls inside runTest(testDispatcher) work. The SUT's own
        // CoroutineScope(Dispatchers.IO) is independent of Dispatchers.Main and is NOT mocked.
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
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    // DrugMasterDao feeds the DrugResolver; PreferenceHelper feeds the config provider.
    private fun createRepo(): StockHl7Repository = StockHl7Repository(
        context = context,
        stockTxnDao = stockTxnDao,
        stockBottleInfoDao = stockBottleInfoDao,
        operatorNameProvider = operatorNameProvider,
        stockBatchDao = stockBatchDao,
        hl7MessageSender = hl7MessageSender,
        drugResolver = DrugResolver(drugMasterDao, drugRepository, drugImageDownloader, regionProvider),
        notifier = notifier,
        hl7ConfigProvider = Hl7ConfigProvider(context, preferenceHelper),
    )

    // ─────────────────────────────── message builders ───────────────────────────────

    /** INR^U06 inventory request in the PMS layout: drug in INV-1, status in INV-2. */
    private fun inventoryMessage(
        ndc: String = "12345",
        drugName: String = "Aspirin",
        controlId: String = "MSG1",
    ): HL7Message =
        parseHl7Message(
            listOf(
                mshSegment("INR", "U06", controlId),
                "INV|$ndc^$drugName^NDC|A^Active",
            ).joinToString("\r")
        )

    @Test
    fun `handleInrInventoryRequest routes inventory request`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc("12345", Region.US) } returns
            DrugMasterEntity(drugId = 5L, drugName = "Aspirin", ndc = "12345")
        coEvery { stockBatchDao.insert(any()) } returns 100L
        coEvery { stockTxnDao.upsertPreservingId(any()) } returns 1L

        val msg = inventoryMessage()
        repo.handleInrInventoryRequest(msg)

        coVerify(timeout = 3000) { stockBatchDao.insert(any()) }
    }

    // ─────────────────────────────── sendInventory ───────────────────────────────

    @Test
    fun `sendInventory returns when batch null`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { stockBatchDao.getById(100L) } returns null

        repo.sendInventory(100L)

        coVerify(exactly = 0) { hl7MessageSender.sendRaw(any()) }
    }

    @Test
    fun `sendInventory success marks batch synced`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { stockBatchDao.getById(100L) } returns StockBatchEntity(batchId = 100L, requestIdFromPMS = "REQ")
        coEvery { stockBottleInfoDao.getByBatchId(100L) } returns emptyList()
        coEvery { hl7MessageSender.sendRaw(any()) } returns Result.success(SUCCESS_ACK)

        repo.sendInventory(100L)

        coVerify { stockBatchDao.markBatchSynced(100L) }
    }

    @Test
    fun `sendInventory failure does not mark synced`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { stockBatchDao.getById(100L) } returns StockBatchEntity(batchId = 100L, requestIdFromPMS = "REQ")
        coEvery { stockBottleInfoDao.getByBatchId(100L) } returns emptyList()
        coEvery { hl7MessageSender.sendRaw(any()) } returns Result.failure(RuntimeException("send fail"))

        repo.sendInventory(100L)

        coVerify(exactly = 0) { stockBatchDao.markBatchSynced(any()) }
    }

    @Test
    fun `sendInventory swallows exceptions`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { stockBatchDao.getById(100L) } throws RuntimeException("db boom")

        repo.sendInventory(100L)

        coVerify(exactly = 0) { hl7MessageSender.sendRaw(any()) }
    }

    @Test
    fun `sendInventory rethrows cancellation instead of logging it as a send failure`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { stockBatchDao.getById(100L) } throws CancellationException("resend cancelled")

        val thrownBySendInventory = runCatching { repo.sendInventory(100L) }.exceptionOrNull()

        assertTrue(thrownBySendInventory is CancellationException)
    }

    // ─────────────────────────────── large batch (chunked) sync ───────────────────────────────

    /**
     * Builds enough distinct (ndc, lot, expiry) rows to produce ~10000 INV segments once
     * grouped by InventoryMessageBuilder — with maxRowsPerChunk=200 this yields 50 chunks.
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
    fun `sendInventory large batch sends all chunks in order then marks synced`() =
        runTest(testDispatcher) {
            val repo = createRepo()
            coEvery { stockBatchDao.getById(100L) } returns StockBatchEntity(batchId = 100L, requestIdFromPMS = "REQ")
            coEvery { stockBottleInfoDao.getByBatchId(100L) } returns largeBatchTxns(10000)
            coEvery { hl7MessageSender.sendRaw(any()) } returns Result.success(SUCCESS_ACK)

            repo.sendInventory(100L)

            // 10000 distinct ndc/lot/expiry rows chunked at 200 rows/chunk => 50 chunks.
            coVerify(exactly = 50) { hl7MessageSender.sendRaw(any()) }
            for (chunkIndex in 1..50) {
                coVerify { stockBatchDao.markChunkAcked(100L, chunkIndex) }
            }
            coVerify { stockBatchDao.setTotalChunks(100L, 50) }
            coVerify { stockBatchDao.markBatchSynced(100L) }
        }

    @Test
    fun `sendInventory large batch stops and stays unsynced on mid-batch chunk failure`() =
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

            repo.sendInventory(100L)

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
    fun `resendPendingBatches resumes a large batch from lastAckedChunkIndex and syncs`() =
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

            repo.resendPendingBatches()

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
            // resendPendingBatches() as soon as PMS (re)connects — this is the
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
            repo.resendPendingBatches()

            coVerify(timeout = 3000, exactly = 50) { hl7MessageSender.sendRaw(any()) }
            coVerify(timeout = 3000) { stockBatchDao.markBatchSynced(100L) }
        }

    // ─────────────────────────────── resendPendingBatches ───────────────────────────────

    @Test
    fun `resendPendingBatches empty returns`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { stockBatchDao.getUnsyncedCompletedBatchesOnce() } returns emptyList()

        repo.resendPendingBatches()

        // Synchronize on the coroutine having read the unsynced list before asserting getById
        // was never invoked.
        coVerify(timeout = 3000) { stockBatchDao.getUnsyncedCompletedBatchesOnce() }
        coVerify(exactly = 0) { stockBatchDao.getById(any()) }
    }

    @Test
    fun `resendPendingBatches non-empty loops`() = runTest(testDispatcher) {
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

        repo.resendPendingBatches()

        coVerify(timeout = 3000) { stockBatchDao.markBatchSynced(100L) }
    }

    // Fake unsynced-batch table: the resend reads it, markBatchSynced removes from it.
    private fun stubUnsyncedCompletedBatches(vararg initialBatchIds: Long): MutableSet<Long> {
        val unsyncedBatchIds = ConcurrentHashMap.newKeySet<Long>().apply { addAll(initialBatchIds.toList()) }
        coEvery { stockBatchDao.getUnsyncedCompletedBatchesOnce() } answers {
            unsyncedBatchIds.sorted().map { unsyncedBatchId ->
                com.dispensesure.retail.core.room.models.dtos.BatchSummaryDto(
                    batchId = unsyncedBatchId,
                    createdAt = 0L,
                    uniqueNdcCount = 0,
                    status = "COMPLETED",
                    bucketId = null,
                    requestIdFromPMS = "REQ",
                )
            }
        }
        coEvery { stockBatchDao.markBatchSynced(any()) } answers { unsyncedBatchIds.remove(firstArg<Long>()) }
        coEvery { stockBatchDao.getById(any()) } answers {
            StockBatchEntity(batchId = firstArg(), requestIdFromPMS = "REQ")
        }
        coEvery { stockBottleInfoDao.getByBatchId(any()) } returns emptyList()
        return unsyncedBatchIds
    }

    @Test
    fun `END COUNT during a running resend pass still gets sent`() = runTest(testDispatcher) {
        val repo = createRepo()
        val unsyncedBatchIds = stubUnsyncedCompletedBatches(100L)
        val firstPassSending = CountDownLatch(1)
        val releaseFirstPass = CountDownLatch(1)
        coEvery { hl7MessageSender.sendRaw(any()) } answers {
            firstPassSending.countDown()
            releaseFirstPass.await(3, TimeUnit.SECONDS)
            Result.success(SUCCESS_ACK)
        }

        // Reconnect pass is mid-send on batch 100 when END COUNT completes batch 9.
        repo.resendPendingBatches()
        assertTrue(firstPassSending.await(3, TimeUnit.SECONDS))
        unsyncedBatchIds.add(9L)
        repo.resendPendingBatches()
        releaseFirstPass.countDown()

        coVerify(timeout = 3000) { stockBatchDao.markBatchSynced(9L) }
        coVerify(exactly = 1) { stockBatchDao.markBatchSynced(100L) }
    }

    @Test
    fun `triggers during a running pass never send the same batch twice`() = runTest(testDispatcher) {
        val repo = createRepo()
        stubUnsyncedCompletedBatches(100L)
        val firstPassSending = CountDownLatch(1)
        val releaseFirstPass = CountDownLatch(1)
        coEvery { hl7MessageSender.sendRaw(any()) } answers {
            firstPassSending.countDown()
            releaseFirstPass.await(3, TimeUnit.SECONDS)
            Result.success(SUCCESS_ACK)
        }

        repo.resendPendingBatches()
        assertTrue(firstPassSending.await(3, TimeUnit.SECONDS))
        repo.resendPendingBatches()
        repo.resendPendingBatches()
        releaseFirstPass.countDown()

        // The queued rerun reads the list after batch 100 is synced, so it sends nothing.
        coVerify(timeout = 3000, exactly = 2) { stockBatchDao.getUnsyncedCompletedBatchesOnce() }
        coVerify(exactly = 1) { hl7MessageSender.sendRaw(any()) }
        coVerify(exactly = 1) { stockBatchDao.markBatchSynced(100L) }
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
        coEvery { drugMasterDao.getDrugByNdc("12345", Region.US) } returns
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
        coEvery { drugMasterDao.getDrugByNdc("12345", Region.US) } returns
            DrugMasterEntity(drugId = 5L, drugName = "", ndc = "12345")
        coEvery { drugRepository.getDrugInfoByNdc(any<GetNdcRequestModel>()) } returns serverDrugInfo()
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
        coEvery { drugMasterDao.getDrugByNdc("12345", Region.US) } returns null
        coEvery { drugRepository.getDrugInfoByNdc(any<GetNdcRequestModel>()) } returns serverDrugInfo()
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
        coEvery { drugMasterDao.getDrugByNdc("12345", Region.US) } returns null
        coEvery { drugRepository.getDrugInfoByNdc(any<GetNdcRequestModel>()) } returns
            serverDrugInfo(genericName = null)
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
        coEvery { drugMasterDao.getDrugByNdc(any(), any()) } returns null
        coEvery { drugRepository.getDrugInfoByNdc(any<GetNdcRequestModel>()) } returns
            serverDrugInfo(ndc = "", genericName = null)
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
        coEvery { drugMasterDao.getDrugByNdc("12345", Region.US) } returns null
        coEvery { drugRepository.getDrugInfoByNdc(any<GetNdcRequestModel>()) } returns
            serverDrugInfo(genericName = null)

        val msg = inventoryMessage(drugName = "")
        repo.handleInrInventoryRequest(msg)

        verify(timeout = 3000) { notifier.show(any(), any()) }
        coVerify(exactly = 0) { stockBatchDao.insert(any()) }
    }

    @Test
    fun `handleInrInventoryRequest api throws continues`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc("12345", Region.US) } returns null
        coEvery { drugRepository.getDrugInfoByNdc(any<GetNdcRequestModel>()) } throws
            RuntimeException("api boom")

        val msg = inventoryMessage()
        repo.handleInrInventoryRequest(msg)

        verify(timeout = 3000) { notifier.show(any(), any()) }
        coVerify(exactly = 0) { stockBatchDao.insert(any()) }
    }

    @Test
    fun `sendInventory never sends a tutorial batch`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { stockBatchDao.getTutorialBatchIds() } returns listOf(100L)
        coEvery { stockBatchDao.getById(100L) } returns StockBatchEntity(batchId = 100L, requestIdFromPMS = "REQ")

        repo.sendInventory(100L)

        coVerify(exactly = 0) { hl7MessageSender.sendRaw(any()) }
        coVerify(exactly = 0) { stockBatchDao.markBatchSynced(any()) }
    }
}
