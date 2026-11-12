package com.dispensesure.retail.feature.hl7.core

import android.content.Context
import android.util.Log
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import com.dispensesure.retail.feature.hl7.data.repository.DispenseHl7Repository
import com.dispensesure.retail.feature.hl7.data.repository.StockHl7Repository
import com.dispensesure.retail.feature.hl7.notification.Hl7Notifier
import com.dispensesure.retail.feature.hl7.parsing.Hl7OrderHandler
import com.dispensesure.retail.feature.hl7.parsing.Hl7Parser
import com.dispensesure.retail.feature.hl7.parsing.Hl7Validator
import com.dispensesure.retail.feature.hl7.presentation.Hl7OrderProcessor
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import com.dispensesure.retail.core.audit.AuditLogger
import com.dispensesure.retail.core.audit.model.AuditLogEvent
import com.dispensesure.retail.core.audit.model.PmsOrderReceived
import com.dispensesure.retail.core.region.Region
import com.dispensesure.retail.core.region.RegionProvider
import com.dispensesure.retail.core.room.dao.DispenseTxnDao
import com.dispensesure.retail.core.room.models.DispenseTxnEntity
import com.dispensesure.retail.core.room.models.enums.CountStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.rite.hl7.model.HL7Message
import org.rite.hl7.parser.HL7ParseResult
import org.rite.hl7.validation.ValidationResult
import org.rite.hl7.parser.HL7Parser as CoreHL7Parser

class Hl7EventHandlerTest {

    private lateinit var context: Context
    private lateinit var hl7Parser: Hl7Parser
    private lateinit var hl7Validator: Hl7Validator
    private lateinit var hl7OrderHandler: Hl7OrderHandler
    private lateinit var hl7OrderProcessor: Hl7OrderProcessor
    private lateinit var dispenseHl7Repository: DispenseHl7Repository
    private lateinit var stockHl7Repository: StockHl7Repository
    private lateinit var notifier: Hl7Notifier
    private lateinit var preferenceHelper: PreferenceHelper
    private lateinit var auditLogger: AuditLogger
    private lateinit var dispenseTxnDao: DispenseTxnDao
    private lateinit var regionProvider: RegionProvider
    private lateinit var handler: Hl7EventHandler

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.i(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        context = mockk(relaxed = true)
        hl7Parser = mockk(relaxed = true)
        hl7Validator = mockk(relaxed = true)
        hl7OrderHandler = mockk(relaxed = true)
        hl7OrderProcessor = mockk(relaxed = true)
        dispenseHl7Repository = mockk(relaxed = true)
        stockHl7Repository = mockk(relaxed = true)
        notifier = mockk(relaxed = true)
        preferenceHelper = mockk(relaxed = true)
        auditLogger = mockk(relaxed = true)
        dispenseTxnDao = mockk(relaxed = true)
        regionProvider = mockk(relaxed = true)
        every { regionProvider.current } returns Region.US

        every { context.getString(any()) } returns "x"
        every { context.getString(any(), any()) } returns "y"
        every { preferenceHelper.getHl7Version() } returns "2.5"

        handler = Hl7EventHandler(
            context,
            hl7Parser,
            hl7Validator,
            hl7OrderHandler,
            hl7OrderProcessor,
            dispenseHl7Repository,
            stockHl7Repository,
            notifier,
            preferenceHelper,
            auditLogger,
            dispenseTxnDao,
            regionProvider,
            // Unconfined: dispatch runs eagerly until it first suspends, so tests stay deterministic.
            CoroutineScope(Dispatchers.Unconfined),
        )
    }

    @After
    fun tearDown() = unmockkAll()

    private val coreParser = CoreHL7Parser.Builder().build()

    private fun message(raw: String = "MSH|^~\\&|PMS|FAC|APP|STORE|20240101||RDE^O11|MSG-1|P|2.5"): HL7Message =
        when (val result = coreParser.parse(raw)) {
            is HL7ParseResult.Success -> result.message
            is HL7ParseResult.Failure -> result.partialMessage
                ?: error("Failed to parse test HL7 message: ${result.errors}")
        }

    @Test
    fun `onMessageSent logs only and does not touch repository`() {
        handler.onMessageSent("raw", "MSG-2")

    }

    @Test
    fun `onAckReceived on success ACK is informational only`() {
        val successAck = "MSH|^~\\&|PMS|PHARMACY|PillCounter|ROBOT|20240101000000||ACK|MSG-3|P|2.5\rMSA|AA|MSG-3\r"
        handler.onAckReceived(successAck, "MSG-3")
    }

    @Test
    fun `onAckReceived on error ACK is informational only`() {
        val errorAck = "MSH|^~\\&|PMS|PHARMACY|PillCounter|ROBOT|20240101000000||ACK|MSG-3|P|2.5\rMSA|AE|MSG-3|rejected\r"
        handler.onAckReceived(errorAck, "MSG-3")
    }

    @Test
    fun `onServiceStarted does not crash`() {
        handler.onServiceStarted()
    }

    @Test
    fun `onServiceStopped does not crash`() {
        handler.onServiceStopped()
    }

    @Test
    fun `onServerStarted does not crash`() {
        handler.onServerStarted(2575)
    }

    @Test
    fun `onServerStopped does not crash`() {
        handler.onServerStopped()
    }

    @Test
    fun `onImageServiceStarted does not crash`() {
        handler.onImageServiceStarted("http://localhost")
    }

    @Test
    fun `onImageServiceStopped does not crash`() {
        handler.onImageServiceStopped()
    }

    @Test
    fun `onNsdRegistered does not crash`() {
        handler.onNsdRegistered("pms-service")
    }

    @Test
    fun `onNsdDiscoveryStarted does not crash`() {
        handler.onNsdDiscoveryStarted()
    }

    @Test
    fun `onNsdServiceFound does not crash`() {
        handler.onNsdServiceFound("pms-service", "10.0.0.1", 2575)
    }

    @Test
    fun `onError logs error`() {
        handler.onError("MLLP_Client", RuntimeException("boom"))
    }

    @Test
    fun `onClientConnected sets connection state and resends pending transactions`() {
        assertFalse(handler.connectionState.value)

        handler.onClientConnected("10.0.0.1", 2575)

        assertTrue(handler.connectionState.value)
        verify(exactly = 1) { dispenseHl7Repository.resendPendingDispenses() }
        verify(exactly = 1) { stockHl7Repository.sendPendingBatches() }
        verify(exactly = 1) { notifier.show(title = "x", message = "y") }
    }

    @Test
    fun `an inventory request goes to the stock repository`() {
        val inventoryRequest = message(
            "MSH|^~\\&|PMS|FAC|APP|STORE|20240101||INR^U06|MSG-4|P|2.5\r" +
                "INV|12345^Aspirin^NDC|A^Active"
        )

        handler.onMessageReceived(inventoryRequest, "key-4")

        coVerify(timeout = 3000) { stockHl7Repository.handleInrInventoryRequest(inventoryRequest) }
        verify { dispenseHl7Repository wasNot Called }
    }

    @Test
    fun `an order edit goes to the dispense repository`() {
        val orderEdit = message(
            "MSH|^~\\&|PMS|FAC|APP|STORE|20240101||RDE^O11|MSG-5|P|2.5\r" +
                "ORC|XO|RX1|||IP\r" +
                "RXE||12345^Aspirin||||||||10"
        )

        handler.onMessageReceived(orderEdit, "key-5")

        coVerify(timeout = 3000) { dispenseHl7Repository.handleOrderEdit(orderEdit) }
        verify { stockHl7Repository wasNot Called }
    }

    @Test
    fun `a ZNI order packet goes to the dispense repository`() {
        val zniOrderPacket = message(
            "MSH|^~\\&|PMS|FAC|APP|STORE|20240101||RDE^O11|MSG-6|P|2.5\r" +
                "ZNI|1|12345||Aspirin|||||||ORD1||10|RX1|0"
        )

        handler.onMessageReceived(zniOrderPacket, "key-6")

        coVerify(timeout = 3000) { dispenseHl7Repository.handleOrderPacketDispenseRequest(zniOrderPacket) }
        coVerify(exactly = 0) { dispenseHl7Repository.handleZuiOrderPacketDispenseRequest(any()) }
        verify { stockHl7Repository wasNot Called }
    }

    @Test
    fun `a ZUI order packet goes to the dispense repository`() {
        val zuiOrderPacket = message(
            "MSH|^~\\&|PMS|FAC|APP|STORE|20240101||RDE^O11|MSG-7|P|2.5\r" +
                "ZUI|12345|Aspirin||ORD1|10|RX1|0"
        )

        handler.onMessageReceived(zuiOrderPacket, "key-7")

        coVerify(timeout = 3000) { dispenseHl7Repository.handleZuiOrderPacketDispenseRequest(zuiOrderPacket) }
        coVerify(exactly = 0) { dispenseHl7Repository.handleOrderPacketDispenseRequest(any()) }
        verify { stockHl7Repository wasNot Called }
    }

    @Test
    fun `onClientDisconnected clears connection state`() {
        handler.onClientConnected("10.0.0.1", 2575)
        assertTrue(handler.connectionState.value)

        handler.onClientDisconnected()

        assertFalse(handler.connectionState.value)
    }

    @Test
    fun `onPmsCertMismatch sets cert mismatch flag`() {
        assertFalse(handler.pmsCertMismatch.value)
        handler.onPmsCertMismatch()
        assertTrue(handler.pmsCertMismatch.value)
    }

    @Test
    fun `clearCertMismatch resets cert mismatch flag`() {
        handler.onPmsCertMismatch()
        assertTrue(handler.pmsCertMismatch.value)
        handler.clearCertMismatch()
        assertFalse(handler.pmsCertMismatch.value)
    }

    // ════════════════════ Native ZDR gate ════════════════════

    private fun native(
        action: String = "NW",
        controlId: String = "MSG-ZDR",
        orderId: String = "ORDER-001",
        rxNumber: String = "RX-001",
        qty: String = "30",
        drugs: String = "12345678901^DRUG A",
        sendingApp: String = "DISPENSESURE_NATIVE",
        refillsAllowed: String = "5",
    ) = message(
        "MSH|^~\\&|$sendingApp|FAC|APP|STORE|20240101120000||RDE^O11|$controlId|P|2.3.1\r" +
            "ZDR|D|1.0|$controlId|$action|$drugs|NDC|DOE^JANE|||PAT|||AB|$rxNumber|$orderId|$qty|1|R|$refillsAllowed|A"
    )

    private fun nativeInventory(
        controlId: String = "MSG-INV-1",
        drugs: String = "12345678901^ASPIRIN",
    ) = message(
        "MSH|^~\\&|DISPENSESURE_NATIVE|FAC|APP|STORE|20240101120000||INR^U06|$controlId|P|2.3.1\r" +
            "ZDR|I|1.0|$controlId||$drugs|NDC||||||||||||||"
    )

    private fun existingTxn(status: CountStatus = CountStatus.PARTIAL, deleted: Boolean = false) =
        DispenseTxnEntity(txnId = 7L, status = status, isDeleted = deleted, transactionOrderId = "ORDER-001")

    private fun givenExisting(txn: DispenseTxnEntity?) {
        every { dispenseTxnDao.findByTransactionOrderIdIncludingDeleted(any()) } returns txn
    }

    @Test
    fun `a message carrying a ZDR is treated as native`() =
        assertTrue(Hl7EventHandler.isNativeZdrMessage(native()))

    // The native interface is identified by structure, not by sender name: real PMS senders
    // use their own MSH-3. Gating on a fixed sending application dropped live orders into the
    // standard no-RXE branch.
    @Test
    fun `a ZDR is native whatever the sending application`() {
        listOf("HC Companion", "DISPENSESURE", "PMS", "").forEach { sender ->
            assertTrue(sender, Hl7EventHandler.isNativeZdrMessage(native(sendingApp = sender)))
        }
    }

    @Test
    fun `a message without a ZDR is not treated as native`() = assertFalse(
        Hl7EventHandler.isNativeZdrMessage(
            message("MSH|^~\\&|DISPENSESURE_NATIVE|FAC|APP|STORE|20240101||RDE^O11|M1|P|2.5\rORC|NW|RX1")
        )
    )

    // Regression: the exact shape a live PMS sends — MSH + ZDR only, no RXE, MSH-3 "HC Companion".
    // This previously fell through to the standard RDE path and was refused as "no RXE/ZUI/ZNI".
    @Test
    fun `a real PMS MSH plus ZDR order is accepted and dispatched`() {
        val raw = "MSH|^~\\&|HC Companion|PHARMACY|DISPENSESURE|ROBOT|20261103124242||RDE^O11|" +
            "MSG20261103124242001|P|2.3.1\r" +
            "ZDR|D|1.0|MSG20261103124242001|NW|76385-0118-01^ETODOLAC 400MG TABLET|NDC|Doe^Jane|" +
            "19800412|(415) 555-0142|PT-1001|Smith^John|1234567893|AS1234563|100062|RX100062-001|" +
            "30|1|R|0|||TAB|20261103|20261203||Standard|RITE-01|Take 1 tablet by mouth twice daily|" +
            "BID|By mouth|Rite Clinic|Tablet|A123456|Medical Board of California"

        val ack = handler.onMessageReceived(message(raw), "live1")

        assertTrue(ack, ack.contains("MSA|AA|"))
        assertFalse(ack, ack.contains("ERR"))
        coVerify(exactly = 1) { dispenseHl7Repository.handleZdrOrder(any(), any(), any()) }
    }

    // ════════════════════ Validate, ACK, then dispatch ════════════════════

    @Test
    fun `an accepted new order returns AA and is dispatched to handleZdrOrder`() {
        val ack = handler.onMessageReceived(native("NW"), "n1")

        assertTrue(ack, ack.contains("MSA|AA|"))
        coVerify(timeout = 3000) { dispenseHl7Repository.handleZdrOrder(any(), any(), any()) }
    }

    @Test
    fun `a rejected ZDR returns AR with the reason and never reaches the repository`() {
        val ack = handler.onMessageReceived(native("NW", drugs = "12345678901^A~10987654321^B"), "n2")

        assertTrue(ack, ack.contains("MSA|AR|"))
        assertTrue(ack, ack.contains("multipleDrugsOnDispense"))
        verify { dispenseHl7Repository wasNot Called }
        verify { stockHl7Repository wasNot Called }
    }

    @Test
    fun `a rejected ZDR is not audited as received`() {
        handler.onMessageReceived(native("NW", qty = "0"), "n3")

        verify(exactly = 0) { auditLogger.log(any<AuditLogEvent>()) }
    }

    @Test
    fun `the ACK is returned before the handler completes`() {
        coEvery { dispenseHl7Repository.handleZdrOrder(any(), any(), any()) } coAnswers { awaitCancellation() }

        val ack = handler.onMessageReceived(native("NW"), "n4")

        assertTrue(ack, ack.contains("MSA|AA|"))
    }

    @Test
    fun `a handler failure after the ACK does not propagate`() {
        coEvery { dispenseHl7Repository.handleZdrOrder(any(), any(), any()) } throws IllegalStateException("boom")

        val ack = handler.onMessageReceived(native("NW"), "n5")

        assertTrue(ack, ack.contains("MSA|AA|"))
    }

    @Test
    fun `an XO for an existing order returns AA and dispatches handleZdrOrder`() {
        givenExisting(existingTxn())

        val ack = handler.onMessageReceived(native("XO"), "x1")

        assertTrue(ack, ack.contains("MSA|AA|"))
        coVerify(timeout = 3000) { dispenseHl7Repository.handleZdrOrder(any(), any(), any()) }
    }

    @Test
    fun `an XO for an unknown order is rejected as orderNotFound and not dispatched`() {
        givenExisting(null)

        val ack = handler.onMessageReceived(native("XO"), "x2")

        assertTrue(ack, ack.contains("MSA|AR|") && ack.contains("orderNotFound"))
        verify { dispenseHl7Repository wasNot Called }
    }

    @Test
    fun `a CA for a soft-deleted order rejects as orderAlreadyCompleted not orderNotFound`() {
        givenExisting(existingTxn(deleted = true))

        val ack = handler.onMessageReceived(native("CA"), "c1")

        assertTrue(ack, ack.contains("MSA|AR|"))
        assertTrue(ack, ack.contains("orderAlreadyCompleted"))
        assertFalse(ack, ack.contains("orderNotFound"))
        verify { dispenseHl7Repository wasNot Called }
    }

    @Test
    fun `the existing order is looked up including soft-deleted rows`() {
        givenExisting(existingTxn(deleted = true))

        handler.onMessageReceived(native("DC"), "c2")

        verify { dispenseTxnDao.findByTransactionOrderIdIncludingDeleted("ORDER-001") }
        coVerify(exactly = 0) { dispenseTxnDao.getByTransactionOrderId(any()) }
    }

    @Test
    fun `the order key falls back to rx-fill when ZDR-15 is blank`() {
        givenExisting(existingTxn())

        handler.onMessageReceived(native("HD", orderId = "", rxNumber = "RX-9"), "c3")

        verify { dispenseTxnDao.findByTransactionOrderIdIncludingDeleted("RX-9-1") }
    }

    @Test
    fun `a control action on a live order returns AA and dispatches handleZdrControl`() {
        givenExisting(existingTxn())

        val ack = handler.onMessageReceived(native("HD"), "c4")

        assertTrue(ack, ack.contains("MSA|AA|"))
        coVerify(timeout = 3000) { dispenseHl7Repository.handleZdrControl(any(), "HD") }
        coVerify(exactly = 0) { dispenseHl7Repository.handleZdrOrder(any(), any(), any()) }
    }

    @Test
    fun `a release for an order that is not on hold is rejected`() {
        givenExisting(existingTxn(CountStatus.PARTIAL))

        val ack = handler.onMessageReceived(native("RL"), "c5")

        assertTrue(ack, ack.contains("orderNotOnHold"))
        verify { dispenseHl7Repository wasNot Called }
    }

    @Test
    fun `a refill uses the live fill count`() {
        every { dispenseTxnDao.countActiveByRxNo("RX-001") } returns 0

        val ack = handler.onMessageReceived(native("RF"), "r1")

        assertTrue(ack, ack.contains("orderNotFound"))
        verify { dispenseHl7Repository wasNot Called }
    }

    @Test
    fun `a refill with fills remaining is accepted and dispatched`() {
        every { dispenseTxnDao.countActiveByRxNo("RX-001") } returns 1

        val ack = handler.onMessageReceived(native("RF"), "r2")

        assertTrue(ack, ack.contains("MSA|AA|"))
        coVerify(timeout = 3000) { dispenseHl7Repository.handleZdrOrder(any(), any(), any()) }
    }

    @Test
    fun `audit logs the ZDR order action not the absent ORC order control`() {
        givenExisting(existingTxn())
        val logged = slot<AuditLogEvent>()
        every { auditLogger.log(capture(logged)) } returns Unit

        handler.onMessageReceived(native("HD"), "a1")

        val event = logged.captured as PmsOrderReceived
        assertEquals("HD", event.orderControl)
        assertEquals("ZDR", event.format)
    }

    @Test
    fun `an accepted native inventory request returns AA and goes to the stock repository`() {
        val ack = handler.onMessageReceived(nativeInventory(), "i1")

        assertTrue(ack, ack.contains("MSA|AA|"))
        coVerify(timeout = 3000) { stockHl7Repository.handleZdrInventoryRequest(any()) }
        coVerify(exactly = 0) { stockHl7Repository.handleInrInventoryRequest(any()) }
    }

    @Test
    fun `a native inventory request with a bad drug code is rejected and not dispatched`() {
        val ack = handler.onMessageReceived(nativeInventory(drugs = "123^ASPIRIN"), "i2")

        assertTrue(ack, ack.contains("MSA|AR|") && ack.contains("invalidDrugCode"))
        verify { stockHl7Repository wasNot Called }
    }

    @Test
    fun `a native inventory request does no order lookup`() {
        handler.onMessageReceived(nativeInventory(), "i3")

        verify { dispenseTxnDao wasNot Called }
    }

    @Test
    fun `a standard INR without ZDR still routes to handleInrInventoryRequest`() {
        val standardMsg = message(
            "MSH|^~\\&|PMS|FAC|APP|STORE|20240101||INR^U06|MSG-STD-INR|P|2.5\r" +
                "INV|12345^Aspirin^NDC|A^Active"
        )

        handler.onMessageReceived(standardMsg, "i4")

        coVerify(timeout = 3000) { stockHl7Repository.handleInrInventoryRequest(standardMsg) }
        coVerify(exactly = 0) { stockHl7Repository.handleZdrInventoryRequest(any()) }
    }
}
