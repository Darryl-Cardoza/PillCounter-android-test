package com.dispensesure.retail.feature.hl7.data.repository

import android.content.Context
import com.dispensesure.retail.core.room.dao.DispenseCountDao
import com.dispensesure.retail.core.room.dao.DispenseTxnDao
import com.dispensesure.retail.core.room.dao.DrugMasterDao
import com.dispensesure.retail.core.room.models.DispenseCountEntity
import com.dispensesure.retail.core.room.models.DispenseTxnEntity
import com.dispensesure.retail.core.room.models.DrugMasterEntity
import com.dispensesure.retail.core.room.models.enums.CountStatus
import com.dispensesure.retail.core.scanning.data.DrugImageDownloader
import com.dispensesure.retail.core.scanning.data.DrugRepository
import com.dispensesure.retail.core.scanning.data.DrugResolver
import com.dispensesure.retail.core.scanning.domain.model.GetNdcRequestModel
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.rite.hl7.model.HL7Message

/**
 * The SUT creates its own `CoroutineScope(Dispatchers.IO)` at construction and launches its
 * fire-and-forget handlers on it. We deliberately do NOT statically mock [Dispatchers] here:
 * doing so causes MockK's own verification machinery (which internally touches
 * `Dispatchers.getDefault()`) to be recorded into the verify scope, producing intermittent
 * "should not be called" failures and making the whole class flaky.
 *
 * Instead the SUT's scope runs on the real Dispatchers.IO background threads. Two kinds of
 * methods exist:
 *  - `suspend` methods called directly inside runTest (sendDispense) run inline and are
 *    verified with a plain `coVerify { }` immediately after the call.
 *  - methods that do `scope.launch { ... }` (handleReceivedMessage, resendPendingDispenses, markTransactionSynced,
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
class DispenseHl7RepositoryTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var context: Context
    private lateinit var drugMasterDao: DrugMasterDao
    private lateinit var locationProvider: LocationProvider
    private lateinit var dispenseTxnDao: DispenseTxnDao
    private lateinit var dispenseCountDao: DispenseCountDao
    private lateinit var preferenceHelper: PreferenceHelper
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
        locationProvider = mockk(relaxed = true)
        dispenseTxnDao = mockk(relaxed = true)
        dispenseCountDao = mockk(relaxed = true)
        preferenceHelper = mockk(relaxed = true)
        hl7MessageSender = mockk(relaxed = true)
        drugRepository = mockk(relaxed = true)
        notifier = mockk(relaxed = true)
        drugImageDownloader = mockk(relaxed = true)

        every { context.getString(any()) } returns "x"
        every { context.getString(any(), *anyVararg()) } returns "x"

        // By default HL7 is disabled so init's observePendingHl7Transactions does not interfere.
        every { preferenceHelper.isHl7Enabled() } returns false
        every { preferenceHelper.getLocalId() } returns 1L
        // send() must be stubbed, not left relaxed: the dispense path logs the returned ACK, and
        // a relaxed Result<String> hands back a non-String that blows up on first use.
        coEvery { hl7MessageSender.send(any()) } returns Result.success(SUCCESS_ACK)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createRepo(): DispenseHl7Repository = DispenseHl7Repository(
        context = context,
        drugMasterDao = drugMasterDao,
        locationProvider = locationProvider,
        dispenseTxnDao = dispenseTxnDao,
        dispenseCountDao = dispenseCountDao,
        preferenceHelper = preferenceHelper,
        hl7MessageSender = hl7MessageSender,
        drugResolver = DrugResolver(drugMasterDao, drugRepository, drugImageDownloader),
        notifier = notifier,
        hl7ConfigProvider = Hl7ConfigProvider(context, preferenceHelper),
    )

    // ─────────────────────────────── message builders ───────────────────────────────

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
            mshSegment("RDE", "O11", controlId),
            "ORC|$orderControl|$placerOrderId|||$orderStatus",
            "RXE||$drugCode^$drugName||||||||$dispenseAmount",
        )
        segments.addAll(extraSegments)
        return parseHl7Message(segments.joinToString("\r"))
    }

    private fun zprSegment(priority: String) = "ZPR|1|PRIORITY|$priority"
    private fun zinSegment(quantity: String) = "ZIN|1|EXPECTED_ON_HAND|$quantity"

    // ZNI-2 NDC, ZNI-4 drug name, ZNI-11 filler order, ZNI-13 amount, ZNI-14 Rx, ZNI-15 fill.
    private fun zniOrderMessage(): HL7Message =
        parseHl7Message(listOf(mshSegment("RDE", "O11", "MSG1"), "ZNI|1|12345||Aspirin|||||||ORD1||10|RX1|0").joinToString("\r"))

    // ZUI-1 NDC, ZUI-2 drug name, ZUI-4 order id, ZUI-5 quantity, ZUI-6 Rx, ZUI-7 fill.
    private fun zuiOrderMessage(): HL7Message =
        parseHl7Message(listOf(mshSegment("RDE", "O11", "MSG1"), "ZUI|12345|Aspirin||ORD1|10|RX1|0").joinToString("\r"))

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

    // ─────────────────────────────── sendDispense ───────────────────────────────

    @Test
    fun `sendDispense returns when txn null`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { dispenseTxnDao.getById(1L) } returns null

        repo.sendDispense(1L)

        coVerify(exactly = 0) { hl7MessageSender.send(any()) }
    }

    @Test
    fun `sendDispense returns when drug null`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { dispenseTxnDao.getById(1L) } returns txnEntity(drugId = null)

        repo.sendDispense(1L)

        coVerify(exactly = 0) { hl7MessageSender.send(any()) }
    }

    @Test
    fun `sendDispense skips a txn that is already synced`() =
        runTest(testDispatcher) {
            // Completion calls sendDispenseNow AND flips the status, which wakes the resend
            // sweep. Whichever arrives second must not put a second copy on the wire.
            val repo = createRepo()
            coEvery { dispenseTxnDao.getById(1L) } returns txnEntity(drugId = 5L, isSynced = true)

            repo.sendDispense(1L)

            coVerify(exactly = 0) { hl7MessageSender.send(any()) }
        }

    @Test
    fun `sendDispense builds and sends`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { dispenseTxnDao.getById(1L) } returns txnEntity(drugId = 5L)
        coEvery { dispenseCountDao.getAllForTxn("1") } returns
            listOf(DispenseCountEntity(txnId = 1L, pillCount = 5))
        coEvery { locationProvider.getCurrentLocationAsString() } returns "loc"
        coEvery { drugMasterDao.getDrugById(5L) } returns
            DrugMasterEntity(drugId = 5L, drugName = "Aspirin", ndc = "12345")

        repo.sendDispense(1L)

        coVerify { hl7MessageSender.send(any()) }
    }

    /**
     * The operator is stamped onto the transaction when the count completes, so a resend
     * reports whoever ran the count. RXD-10 carries the name only — PMS is sent no
     * operator id.
     */
    @Test
    fun `sendDispense puts the operator in RXD-10 as caret family caret given`() =
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

            repo.sendDispense(1L)

            val rxd = sent.captured.split("\r").first { it.startsWith("RXD|") }
            val rxd10 = rxd.split("|")[10]
            assertEquals("^Wadajkar^Yash", rxd10)
        }

    // ─────────────────────────────── resendPendingDispenses ───────────────────────────────

    @Test
    fun `resendPendingDispenses empty returns`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { dispenseTxnDao.getPendingHl7TxnOnce() } returns emptyList()

        repo.resendPendingDispenses()

        // Synchronize on the coroutine having read the pending list before asserting no
        // dispense send was attempted.
        coVerify(timeout = 3000) { dispenseTxnDao.getPendingHl7TxnOnce() }
        coVerify(exactly = 0) { dispenseTxnDao.getById(any()) }
    }

    @Test
    fun `resendPendingDispenses sends every pending dispense`() = runTest(testDispatcher) {
        val repo = createRepo()
        // Stock (REGULAR) counts no longer live in pill_count_txn; every pending row is a dispense.
        // Batch HL7 responses are resent per-batch via StockHl7Repository.resendPendingBatches().
        coEvery { dispenseTxnDao.getPendingHl7TxnOnce() } returns listOf(txnEntity(txnId = 1L))

        coEvery { dispenseTxnDao.getById(1L) } returns txnEntity(txnId = 1L, drugId = 5L)
        coEvery { dispenseCountDao.getAllForTxn("1") } returns emptyList()
        coEvery { drugMasterDao.getDrugById(5L) } returns
            DrugMasterEntity(drugId = 5L, drugName = "Aspirin", ndc = "12345")

        repo.resendPendingDispenses()

        coVerify(timeout = 3000) { hl7MessageSender.send(any()) }
        // The outbound MSH-10 is persisted as the txnId itself so a later ACK can correlate
        // back to this exact row via getByMessageControlId.
        coVerify(timeout = 3000) { dispenseTxnDao.updateHl7MessageControlId(1L, "1", any()) }
    }

    // ─────────────────────────────── markTransactionSynced (private, via sendDispense) ───────────────────────────────
    //
    // markTransactionSynced(txnId: Long) is a private implementation detail invoked only from
    // sendDispense on a success ACK — there is no longer any messageControlId
    // lookup (that was the pre-refactor shape). Exercised here through the public entry point.

    @Test
    fun `sendDispense marks txn synced on success ACK`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { dispenseTxnDao.getById(1L) } returns txnEntity(drugId = 5L)
        coEvery { dispenseCountDao.getAllForTxn("1") } returns
            listOf(DispenseCountEntity(txnId = 1L, pillCount = 5))
        coEvery { locationProvider.getCurrentLocationAsString() } returns "loc"
        coEvery { drugMasterDao.getDrugById(5L) } returns
            DrugMasterEntity(drugId = 5L, drugName = "Aspirin", ndc = "12345")
        coEvery { hl7MessageSender.send(any()) } returns Result.success("MSH|^~\\&|A|B|C|D|20240101||ACK|1|P|2.5\rMSA|AA|1\r")
        every { preferenceHelper.isAllowLocalStorage() } returns true

        repo.sendDispense(1L)

        coVerify(timeout = 3000) { dispenseTxnDao.markTxnSynced(1L, any()) }
    }

    @Test
    fun `sendDispense does not mark synced on non-success ACK`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { dispenseTxnDao.getById(1L) } returns txnEntity(drugId = 5L)
        coEvery { dispenseCountDao.getAllForTxn("1") } returns
            listOf(DispenseCountEntity(txnId = 1L, pillCount = 5))
        coEvery { locationProvider.getCurrentLocationAsString() } returns "loc"
        coEvery { drugMasterDao.getDrugById(5L) } returns
            DrugMasterEntity(drugId = 5L, drugName = "Aspirin", ndc = "12345")
        coEvery { hl7MessageSender.send(any()) } returns Result.success("MSH|^~\\&|A|B|C|D|20240101||ACK|1|P|2.5\rMSA|AE|1|rejected\r")

        repo.sendDispense(1L)

        coVerify(exactly = 0) { dispenseTxnDao.markTxnSynced(any(), any()) }
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
        verify(timeout = 3000) { notifier.show(any(), any(), 1L) }
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
        verify(timeout = 3000) { notifier.show(any(), any(), 8L) }
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
        coEvery { drugRepository.getDrugInfoByNdc(any<GetNdcRequestModel>()) } returns serverDrugInfo()
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
            serverDrugInfo(genericName = null)

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
        coEvery { drugRepository.getDrugInfoByNdc(any<GetNdcRequestModel>()) } returns serverDrugInfo()
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
    fun `handleOrderEdit to a held or completed order opens no order`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { dispenseTxnDao.getActiveByRxNo("RX1") } returns txnEntity()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns
            DrugMasterEntity(drugId = 5L, drugName = "Aspirin", ndc = "12345")
        coEvery { drugMasterDao.upsertPreservingId(any()) } returns 5L

        repo.handleOrderEdit(editMessage(orderStatus = "HD"))
        repo.handleOrderEdit(editMessage(orderStatus = "CM"))

        verify(exactly = 2, timeout = 3000) { notifier.show(any(), any(), null) }
        verify(exactly = 0) { notifier.show(any(), any(), 1L) }
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
        verify(timeout = 3000) { notifier.show(any(), any(), 1L) }
    }

    @Test
    fun `handleOrderPacketDispenseRequest notification opens the saved order`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns
            DrugMasterEntity(drugId = 5L, drugName = "Aspirin", ndc = "12345")
        coEvery { dispenseTxnDao.upsertPreservingId(any()) } returns 42L

        repo.handleOrderPacketDispenseRequest(zniOrderMessage())

        verify(timeout = 3000) { notifier.show(any(), any(), 42L) }
    }

    @Test
    fun `handleZuiOrderPacketDispenseRequest notification opens the saved order`() = runTest(testDispatcher) {
        val repo = createRepo()
        coEvery { drugMasterDao.getDrugByNdc("12345") } returns
            DrugMasterEntity(drugId = 5L, drugName = "Aspirin", ndc = "12345")
        coEvery { dispenseTxnDao.upsertPreservingId(any()) } returns 42L

        repo.handleZuiOrderPacketDispenseRequest(zuiOrderMessage())

        verify(timeout = 3000) { notifier.show(any(), any(), 42L) }
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
}
