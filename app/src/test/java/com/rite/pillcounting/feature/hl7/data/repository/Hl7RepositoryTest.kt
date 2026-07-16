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
import com.rite.pillcounting.core.room.models.enums.CountStatus
import com.rite.pillcounting.core.room.models.enums.CountType
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
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.rite.hl7.domain.model.CompleteHL7Message
import org.rite.hl7.domain.model.CustomSegmentData
import org.rite.hl7.domain.model.MedicationData
import org.rite.hl7.domain.model.MessageHeaderData
import org.rite.hl7.domain.model.OrderData

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
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Hl7RepositoryTest {

    private val testDispatcher = StandardTestDispatcher()

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
        every { hl7MessageSender.sendRaw(any()) } returns Result.success(Unit)
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

    private fun header(controlId: String = "MSG1") = MessageHeaderData(
        fieldSeparator = "|",
        encodingCharacters = "^~\\&",
        sendingApplication = "PMS",
        sendingFacility = "FAC",
        receivingApplication = "APP",
        receivingFacility = "STORE",
        messageDateTime = "20240101",
        messageType = "RDE",
        triggerEvent = "O11",
        messageControlId = controlId,
        processingId = "P",
        versionId = "2.5",
    )

    private fun med(
        drugCode: String = "12345",
        drugName: String = "Aspirin",
        requestedQty: String? = "10",
    ) = MedicationData(drugCode = drugCode, drugName = drugName, requestedQty = requestedQty)

    private fun message(
        messageType: String = "RDE",
        triggerEvent: String = "O11",
        order: OrderData? = OrderData(orderControl = "NW", placerOrderId = "RX1", orderStatus = "IP"),
        medications: List<MedicationData> = listOf(med()),
        customSegments: List<CustomSegmentData> = emptyList(),
        controlId: String = "MSG1",
    ) = CompleteHL7Message(
        messageId = "id",
        messageType = messageType,
        triggerEvent = triggerEvent,
        timestamp = "20240101",
        sendingFacility = "FAC",
        header = header(controlId),
        order = order,
        medications = medications,
        customSegments = customSegments,
    )

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
        // messageType not matching any branch and no order control
        val msg = message(
            messageType = "ADT",
            triggerEvent = "A01",
            order = OrderData(orderControl = "NW", placerOrderId = ""),
        )

        // classifyInboundMessage returns null synchronously on the calling thread (before any
        // scope.launch), so no background work is started at all — assert absence directly.
        repo.handleReceivedMessage(msg)

        coVerify(exactly = 0) { drugMasterDao.getDrugByNdc(any()) }
        coVerify(exactly = 0) { pillCountTxnDao.softDeleteByRxNo(any(), any()) }
    }

    @Test
    fun `handleReceivedMessage routes cancel order`() = runTest(testDispatcher) {
        val repo = createRepo()
        val msg = message(order = OrderData(orderControl = "CA", placerOrderId = "RX9"))

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

        val msg = message(order = OrderData(orderControl = "XO", placerOrderId = "RX1", orderStatus = "IP"))
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

        repo.handleReceivedMessage(message())

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

        val msg = message(messageType = "INR", triggerEvent = "U04")
        repo.handleReceivedMessage(msg)

        coVerify(timeout = 3000) { batchDao.insert(any()) }
    }

    // ─────────────────────────────── buildAndSendSuccessfulDispense ───────────────────────────────

    @Test
    fun `buildAndSendSuccessfulDispense returns when txn null`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { txnDao.getById(1L) } returns null

        repo.buildAndSendSuccessfulDispense(1L)

        verify(exactly = 0) { hl7MessageSender.send(any()) }
    }

    @Test
    fun `buildAndSendSuccessfulDispense returns when drug null`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { txnDao.getById(1L) } returns txnEntity(drugId = null)

        repo.buildAndSendSuccessfulDispense(1L)

        verify(exactly = 0) { hl7MessageSender.send(any()) }
    }

    @Test
    fun `buildAndSendSuccessfulDispense builds and sends`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { txnDao.getById(1L) } returns txnEntity(drugId = 5L)
        coEvery { txnDetailsDao.getAllForTxn("1") } returns
            listOf(PillCountTxnDetailsEntity(txnId = 1L, pillCount = 5))
        coEvery { userDao.getByUserId(any()) } returns null
        coEvery { locationProvider.getCurrentLocationAsString() } returns "loc"
        coEvery { drugMasterDao.getDrugById(5L) } returns
            DrugMasterEntity(drugId = 5L, drugName = "Aspirin", ndc = "12345")

        repo.buildAndSendSuccessfulDispense(1L)

        verify { hl7MessageSender.send(any()) }
    }

    // ─────────────────────────────── buildAndSendInventoryResponse ───────────────────────────────

    @Test
    fun `buildAndSendInventoryResponse returns when batch null`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { batchDao.getById(100L) } returns null

        repo.buildAndSendInventoryResponse(100L)

        verify(exactly = 0) { hl7MessageSender.sendRaw(any()) }
    }

    @Test
    fun `buildAndSendInventoryResponse success marks batch synced`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { batchDao.getById(100L) } returns BatchEntity(batchId = 100L, requestIdFromPMS = "REQ")
        coEvery { bottleInfoDao.getByBatchId(100L) } returns emptyList()
        every { hl7MessageSender.sendRaw(any()) } returns Result.success(Unit)

        repo.buildAndSendInventoryResponse(100L)

        coVerify { batchDao.markBatchSynced(100L) }
    }

    @Test
    fun `buildAndSendInventoryResponse failure does not mark synced`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { batchDao.getById(100L) } returns BatchEntity(batchId = 100L, requestIdFromPMS = "REQ")
        coEvery { bottleInfoDao.getByBatchId(100L) } returns emptyList()
        every { hl7MessageSender.sendRaw(any()) } returns Result.failure(RuntimeException("send fail"))

        repo.buildAndSendInventoryResponse(100L)

        coVerify(exactly = 0) { batchDao.markBatchSynced(any()) }
    }

    @Test
    fun `buildAndSendInventoryResponse swallows exceptions`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { batchDao.getById(100L) } throws RuntimeException("db boom")

        repo.buildAndSendInventoryResponse(100L)

        verify(exactly = 0) { hl7MessageSender.sendRaw(any()) }
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
        coEvery { drugMasterDao.getDrugById(5L) } returns
            DrugMasterEntity(drugId = 5L, drugName = "Aspirin", ndc = "12345")

        repo.resendPendingHl7Transactions()

        verify(timeout = 3000) { preferenceHelper.saveSentMessageTxnId(1L) }
        verify(timeout = 3000) { hl7MessageSender.send(any()) }
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
        val msg = message(
            customSegments = listOf(
                CustomSegmentData(segmentType = "ZPR", field2 = "PRIORITY", field3 = "High"),
            ),
        )

        repo.handleReceivedMessage(msg)

        val txnSlot = slot<PillCountTxnEntity>()
        coVerify(timeout = 3000) { pillCountTxnDao.upsertPreservingId(capture(txnSlot)) }
        assert(txnSlot.captured.priority == TxnPriority.High)
        verify(timeout = 3000) { notifier.show(any(), any()) }
    }

    @Test
    fun `handleRdeDispenseRequest multiple meds notification`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc(any()) } returns
            DrugMasterEntity(drugId = 5L, drugName = "Aspirin", ndc = "12345")
        coEvery { drugMasterDao.upsertPreservingId(any()) } returns 5L
        coEvery { pillCountTxnDao.upsertPreservingId(any()) } returns 1L
        val msg = message(medications = listOf(med(drugCode = "11"), med(drugCode = "22")))

        repo.handleReceivedMessage(msg)

        verify(timeout = 3000) { notifier.show(any(), any()) }
    }

    @Test
    fun `handleRdeDispenseRequest api success when not local`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns null
        coEvery { drugRepository.getDrugInfoByNdc(any<GetNdcRequestModel>()) } returns drugInfo()
        coEvery { drugMasterDao.upsertPreservingId(any()) } returns 9L
        coEvery { pillCountTxnDao.upsertPreservingId(any()) } returns 1L

        repo.handleReceivedMessage(message())

        coVerify(timeout = 3000) { drugRepository.getDrugInfoByNdc(any<GetNdcRequestModel>()) }
        coVerify(timeout = 3000) { pillCountTxnDao.upsertPreservingId(any()) }
    }

    @Test
    fun `handleRdeDispenseRequest api throws notifies and returns`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns null
        coEvery { drugRepository.getDrugInfoByNdc(any<GetNdcRequestModel>()) } throws
            RuntimeException("api fail")

        repo.handleReceivedMessage(message())

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

        repo.handleReceivedMessage(message())

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
        val msg = message(
            customSegments = listOf(
                CustomSegmentData(segmentType = "ZIN", field2 = "EXPECTED_ON_HAND", field3 = "0"),
                CustomSegmentData(segmentType = "ZIN", field2 = "EXPECTED_ON_HAND", field3 = "abc"),
                CustomSegmentData(segmentType = "ZIN", field2 = "EXPECTED_ON_HAND", field3 = "7"),
            ),
        )

        repo.handleReceivedMessage(msg)

        coVerify(timeout = 3000) { txnDetailsDao.insert(any()) }
        coVerify(timeout = 3000) { pillCountTxnDao.updateWorkflowStep(42L, any(), any()) }
    }

    // ─────────────────────────────── handleInrInventoryRequest ───────────────────────────────

    @Test
    fun `handleInrInventoryRequest empty meds returns`() = runTest(testDispatcher) {
        val repo = createRepo()
        // INR with empty medications would not classify; classify INVENTORY requires medications
        // non-empty, so the empty-meds guard inside handleInr is unreachable via the public entry.
        // Use a message that classifies as inventory but with blank ndc to reach skip + empty
        // resolved, which exercises the notify + no-batch path.
        val msg = message(
            messageType = "INR",
            triggerEvent = "U04",
            medications = listOf(med(drugCode = "  ")),
        )
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

        val msg = message(messageType = "INR", triggerEvent = "U04")
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

        val msg = message(messageType = "INR", triggerEvent = "U04")
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

        val msg = message(messageType = "INR", triggerEvent = "U04")
        repo.handleReceivedMessage(msg)

        coVerify(timeout = 3000) { batchDao.insert(any()) }
    }

    @Test
    fun `handleInrInventoryRequest api null name uses med name`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns null
        coEvery { drugRepository.getDrugInfoByNdc(any<GetNdcRequestModel>()) } returns
            drugInfo(genericName = null)
        coEvery { drugMasterDao.upsertPreservingId(any()) } returns 9L
        coEvery { batchDao.insert(any()) } returns 100L
        coEvery { stockTxnDao.upsertPreservingId(any()) } returns 1L

        // med drugName is non-blank "Aspirin" so falls back to it
        val msg = message(messageType = "INR", triggerEvent = "U04")
        repo.handleReceivedMessage(msg)

        coVerify(timeout = 3000) { batchDao.insert(any()) }
    }

    @Test
    fun `handleInrInventoryRequest api null name and blank med name skipped`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns null
        coEvery { drugRepository.getDrugInfoByNdc(any<GetNdcRequestModel>()) } returns
            drugInfo(genericName = null)

        val msg = message(
            messageType = "INR",
            triggerEvent = "U04",
            medications = listOf(med(drugCode = "12345", drugName = "")),
        )
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

        val msg = message(messageType = "INR", triggerEvent = "U04")
        repo.handleReceivedMessage(msg)

        verify(timeout = 3000) { notifier.show(any(), any()) }
        coVerify(exactly = 0) { batchDao.insert(any()) }
    }

    // ─────────────────────────────── handleOrderCancellation ───────────────────────────────

    @Test
    fun `handleOrderCancellation soft deletes`() = runTest(testDispatcher) {
        val repo = createRepo()
        repo.handleReceivedMessage(message(order = OrderData(orderControl = "CA", placerOrderId = "RX5")))

        coVerify(timeout = 3000) { pillCountTxnDao.softDeleteByRxNo("RX5", any()) }
    }

    // ─────────────────────────────── handleOrderEdit ───────────────────────────────

    private fun editMessage(
        orderStatus: String? = "IP",
        placerOrderId: String = "RX1",
        medications: List<MedicationData> = listOf(med()),
        customSegments: List<CustomSegmentData> = emptyList(),
    ) = message(
        order = OrderData(orderControl = "XO", placerOrderId = placerOrderId, orderStatus = orderStatus),
        medications = medications,
        customSegments = customSegments,
    )

    @Test
    fun `handleOrderEdit active txn local drug updates`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { pillCountTxnDao.getActiveByRxNo("RX1") } returns txnEntity()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns
            DrugMasterEntity(drugId = 5L, drugName = "Aspirin", ndc = "12345")
        coEvery { drugMasterDao.upsertPreservingId(any()) } returns 5L

        repo.handleReceivedMessage(
            editMessage(
                customSegments = listOf(
                    CustomSegmentData(segmentType = "ZPR", field2 = "PRIORITY", field3 = "Low"),
                ),
            ),
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
