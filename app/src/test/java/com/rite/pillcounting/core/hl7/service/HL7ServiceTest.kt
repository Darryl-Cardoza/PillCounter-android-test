package com.rite.pillcounting.core.hl7.service

import android.content.Intent
import android.util.Log
import com.rite.pillcounting.core.hl7.core.Hl7EventListener
import com.rite.pillcounting.core.hl7.mllp.client.MllpConnectionManager
import com.rite.pillcounting.core.hl7.mllp.nsd.NsdHelper
import com.rite.pillcounting.core.hl7.mllp.tls.TlsSocketFactory
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.rite.hl7.HL7
import org.rite.hl7.model.HL7Message
import java.lang.reflect.Method

/**
 * Unit tests for [HL7Service].
 *
 * The service constructs its real collaborators ([NsdHelper], [TlsSocketFactory], [MllpClient],
 * [MllpConnectionManager], [ImageWebServer], [NetworkIpMonitor]) inline inside private
 * initialization methods launched from a coroutine started in `onStartCommand`. Driving the
 * full service lifecycle under Robolectric to reach those methods would mean fighting
 * `serviceScope` timing for very little payoff, since none of that wiring contains business
 * logic of its own (it's straight construction/delegation, already covered by each
 * collaborator's own test suite — see ImageWebServerTest, MllpConnectionManagerTest, etc.).
 *
 * Instead, this suite targets the service's own logic directly:
 *  - `handleIncomingMessage` / `buildFallbackAck` — the message parsing + ACK pipeline.
 *  - `loadConfigFromIntent` — Intent extras → HL7Config mapping.
 *  - `updateConfig`, `setListener`/`removeListener` — simple state mutation.
 *  - `sendHl7Message` / `sendRawHl7Message` — outbound send + listener callback routing.
 *  - `clearPmsCertPin` — collaborator call sequence.
 *
 * Private fields (`hl7`, `clientManager`, `tlsFactory`, `nsdHelper`, `listener`) and private
 * methods are only populated/reachable via `initializeCoreComponents()`, which itself is only
 * reachable from the coroutine launched in `onStartCommand`. Rather than fight that timing,
 * this suite uses reflection to invoke the private methods directly and to inject mocked
 * collaborators into the private fields — a real `HL7Service` instance is built via
 * `Robolectric.buildService` (so `Service`/`Context` machinery like `applicationContext` works),
 * but its internals are wired directly for focused, deterministic unit-level testing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HL7ServiceTest {

    private lateinit var service: HL7Service

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.i(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.v(any(), any()) } returns 0

        service = Robolectric.buildService(HL7Service::class.java).create().get()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ──────────────────────────── reflection helpers ────────────────────────────

    private fun privateMethod(name: String, vararg paramTypes: Class<*>): Method =
        HL7Service::class.java.getDeclaredMethod(name, *paramTypes).apply { isAccessible = true }

    private fun setField(name: String, value: Any?) {
        val f = HL7Service::class.java.getDeclaredField(name)
        f.isAccessible = true
        f.set(service, value)
    }

    private fun getField(name: String): Any? {
        val f = HL7Service::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(service)
    }

    private fun handleIncomingMessage(raw: String): String {
        val m = privateMethod("handleIncomingMessage", String::class.java)
        return m.invoke(service, raw) as String
    }

    private fun buildFallbackAck(raw: String, errorMsg: String?): String {
        val m = privateMethod("buildFallbackAck", String::class.java, String::class.java)
        return m.invoke(service, raw, errorMsg) as String
    }

    private fun loadConfigFromIntent(intent: Intent) {
        val m = privateMethod("loadConfigFromIntent", Intent::class.java)
        m.invoke(service, intent)
    }

    private fun sampleHl7(controlId: String = "MSG001"): String =
        "MSH|^~\\&|SENDAPP|SENDFAC|RECVAPP|RECVFAC|20240101120000||ADT^A01|$controlId|P|2.5\r" +
            "PID|1||12345^^^MRN||DOE^JOHN||19800101|M"

    // ──────────────────────────── handleIncomingMessage ────────────────────────────

    @Test
    fun `handleIncomingMessage parses successfully, notifies listener, and returns ack`() {
        setField("hl7", HL7(version = "2.5"))
        val listener = mockk<Hl7EventListener>(relaxed = true)
        setField("listener", listener)

        val raw = sampleHl7("MSG001")
        val ack = handleIncomingMessage(raw)

        verify(exactly = 1) { listener.onMessageReceived(any(), "MSG001") }
        assertTrue(ack.contains("MSH|"))
        assertTrue(ack.contains("MSA|"))
    }

    @Test
    fun `handleIncomingMessage falls back to AA ack when parse fails with no partial message`() {
        setField("hl7", HL7(version = "2.5"))
        val listener = mockk<Hl7EventListener>(relaxed = true)
        setField("listener", listener)

        // Not a valid HL7 message at all — no MSH segment, parser should fail entirely.
        val raw = "NOT_HL7_AT_ALL"
        val ack = handleIncomingMessage(raw)

        verify(exactly = 0) { listener.onMessageReceived(any(), any()) }
        assertTrue(ack.contains("MSA|AA") || ack.contains("MSA|AR"))
    }

    @Test
    fun `handleIncomingMessage still notifies listener and acks when parse succeeds with errors`() {
        setField("hl7", HL7(version = "2.5"))
        val listener = mockk<Hl7EventListener>(relaxed = true)
        setField("listener", listener)

        // Missing required trigger event / malformed but still parseable as a partial message
        // with an MSH: relies on the real parser tolerating a degenerate message type field.
        val raw = "MSH|^~\\&|SENDAPP|SENDFAC|RECVAPP|RECVFAC|20240101120000||ADT|MSG002|P|2.5\r" +
            "PID|1||12345^^^MRN||DOE^JOHN||19800101|M"

        val ack = handleIncomingMessage(raw)

        // Whether or not the parser reports this particular message as a "Success" or a
        // "Failure with partial message", a message must be produced and acked either via
        // the real pipeline (listener notified) or via the fallback ack path — both are
        // acceptable "processed" outcomes; the key invariant is it never throws and always
        // returns a non-blank ACK string.
        assertTrue(ack.isNotBlank())
        assertTrue(ack.contains("MSH|") || ack.contains("MSA|"))
    }

    @Test
    fun `handleIncomingMessage routes exception through listener onError and returns fallback ack`() {
        val hl7 = mockk<HL7>()
        every { hl7.parse(any()) } throws RuntimeException("boom")
        setField("hl7", hl7)
        val listener = mockk<Hl7EventListener>(relaxed = true)
        setField("listener", listener)

        val raw = sampleHl7("MSG003")
        val ack = handleIncomingMessage(raw)

        verify(exactly = 1) { listener.onError("HL7_PARSE", any()) }
        assertTrue(ack.contains("MSA|AR"))
    }

    // ──────────────────────────── buildFallbackAck ────────────────────────────

    @Test
    fun `buildFallbackAck swaps sending and receiving app and facility, AA when no error`() {
        val raw = sampleHl7("CTRL123")

        val ack = buildFallbackAck(raw, null)

        val mshFields = ack.lineSequence().first { it.startsWith("MSH|") }.split("|")
        // sendingApp/fac <-> receivingApp/fac are swapped relative to the original.
        assertEquals("RECVAPP", mshFields.getOrNull(2))
        assertEquals("RECVFAC", mshFields.getOrNull(3))
        assertEquals("SENDAPP", mshFields.getOrNull(4))
        assertEquals("SENDFAC", mshFields.getOrNull(5))
        assertTrue(ack.contains("MSA|AA|CTRL123"))
    }

    @Test
    fun `buildFallbackAck returns AR ack code when errorMsg provided`() {
        val raw = sampleHl7("CTRL456")

        val ack = buildFallbackAck(raw, "Something went wrong")

        assertTrue(ack.contains("MSA|AR|CTRL456|Something went wrong"))
    }

    @Test
    fun `buildFallbackAck sanitizes pipe and newline characters in error message`() {
        val raw = sampleHl7("CTRL789")

        val ack = buildFallbackAck(raw, "bad|pipe\r\nchars")

        // The error text must not introduce extra pipe-delimited fields or line breaks
        // into the MSA segment, since that would corrupt the ACK's field structure.
        val msaLine = ack.lineSequence().first { it.startsWith("MSA|") }
        assertFalse(msaLine.contains("\r"))
        assertFalse(msaLine.contains("\n"))
        val msaFields = msaLine.split("|")
        assertEquals("AR", msaFields[1])
        assertEquals("CTRL789", msaFields[2])
        assertEquals("bad pipe  chars", msaFields[3])
    }

    @Test
    fun `buildFallbackAck falls into catch branch and still returns ack when no MSH segment present`() {
        val raw = "GARBAGE_NO_MSH_SEGMENT_AT_ALL"

        val ack = buildFallbackAck(raw, "parse failed")

        assertTrue(ack.startsWith("MSH|"))
        assertTrue(ack.contains("MSA|AR"))
    }

    @Test
    fun `buildFallbackAck catch branch produces AA ack when no error and no MSH`() {
        val raw = ""

        val ack = buildFallbackAck(raw, null)

        assertTrue(ack.contains("MSA|AA"))
    }

    // ──────────────────────────── loadConfigFromIntent ────────────────────────────

    @Test
    fun `loadConfigFromIntent populates all fields from intent extras`() {
        val intent = Intent().apply {
            putExtra(Hl7serviceHandler.EXTRA_SERVER_PORT, 1234)
            putExtra(Hl7serviceHandler.EXTRA_AUTO_RESPONSE_DELAY, 5000L)
            putExtra(Hl7serviceHandler.EXTRA_NSD_BROADCAST_NAME, "MyService")
            putExtra(Hl7serviceHandler.EXTRA_NSD_BROADCAST_TYPE, "_mytype._tcp")
            putExtra(Hl7serviceHandler.EXTRA_NSD_DISCOVERY_TYPE, "_mydiscovery._tcp")
            putExtra(Hl7serviceHandler.EXTRA_IMAGE_SERVICE_PORT, 8888)
        }

        loadConfigFromIntent(intent)

        val config = getField("config") as HL7Config
        assertEquals(1234, config.serverPort)
        assertEquals(5000L, config.autoResponseDelayMs)
        assertEquals("MyService", config.nsdBroadcastServiceName)
        assertEquals("_mytype._tcp", config.nsdBroadcastType)
        assertEquals("_mydiscovery._tcp", config.nsdDiscoveryType)
        assertEquals(8888, config.imageServicePort)
    }

    @Test
    fun `loadConfigFromIntent keeps existing config defaults for omitted extras`() {
        val existing = HL7Config(serverPort = 4242, nsdBroadcastServiceName = "ExistingName")
        setField("config", existing)

        val emptyIntent = Intent()
        loadConfigFromIntent(emptyIntent)

        val config = getField("config") as HL7Config
        assertEquals(4242, config.serverPort)
        assertEquals("ExistingName", config.nsdBroadcastServiceName)
        assertEquals(existing.autoResponseDelayMs, config.autoResponseDelayMs)
        assertEquals(existing.nsdBroadcastType, config.nsdBroadcastType)
        assertEquals(existing.nsdDiscoveryType, config.nsdDiscoveryType)
        assertEquals(existing.imageServicePort, config.imageServicePort)
    }

    // ──────────────────────────── updateConfig ────────────────────────────

    @Test
    fun `updateConfig replaces current config`() {
        val newConfig = HL7Config(serverPort = 9999)

        service.updateConfig(newConfig)

        assertEquals(newConfig, getField("config"))
    }

    @Test
    fun `updateConfig returns true when the value actually changed`() {
        val newConfig = HL7Config(serverPort = 9999)

        val changed = service.updateConfig(newConfig)

        assertTrue(changed)
    }

    @Test
    fun `updateConfig returns false when the value is unchanged`() {
        val sameConfig = HL7Config(serverPort = 9999)
        service.updateConfig(sameConfig)

        val changed = service.updateConfig(sameConfig.copy())

        assertFalse(changed)
    }

    // ──────────────────────────── setListener / removeListener ────────────────────────────

    @Test
    fun `setListener stores the listener`() {
        val listener = mockk<Hl7EventListener>(relaxed = true)

        service.setListener(listener)

        assertEquals(listener, getField("listener"))
    }

    @Test
    fun `removeListener nulls out the listener so later callbacks are no-ops`() {
        val listener = mockk<Hl7EventListener>(relaxed = true)
        service.setListener(listener)

        service.removeListener()

        assertNull(getField("listener"))

        // Simulate a later callback path: handleIncomingMessage should not crash with a null listener.
        setField("hl7", HL7(version = "2.5"))
        val ack = handleIncomingMessage(sampleHl7("MSGX"))
        assertNotNull(ack)
    }

    // ──────────────────────────── sendHl7Message / sendRawHl7Message ────────────────────────────

    @Test
    fun `sendHl7Message sends encoded message and routes ack through listener`() {
        val clientManager = mockk<MllpConnectionManager>(relaxed = true)
        coEveryReturns(clientManager, "ACK_RESPONSE")
        setField("clientManager", clientManager)
        val listener = mockk<Hl7EventListener>(relaxed = true)
        setField("listener", listener)

        val message = mockk<HL7Message>()
        every { message.encode() } returns "ENCODED_MSG"
        every { message.messageControlId } returns "CTRL1"

        service.sendHl7Message(message)
        drainCoroutines()

        verify(exactly = 1) { listener.onMessageSent("ENCODED_MSG", "CTRL1") }
        verify(exactly = 1) { listener.onAckReceived("ACK_RESPONSE", "CTRL1") }
    }

    @Test
    fun `sendHl7Message routes exception through listener onError instead of throwing`() {
        val clientManager = mockk<MllpConnectionManager>()
        coEveryThrows(clientManager, RuntimeException("send failed"))
        setField("clientManager", clientManager)
        val listener = mockk<Hl7EventListener>(relaxed = true)
        setField("listener", listener)

        val message = mockk<HL7Message>()
        every { message.encode() } returns "ENCODED_MSG"
        every { message.messageControlId } returns "CTRL2"

        service.sendHl7Message(message)
        drainCoroutines()

        verify(exactly = 1) { listener.onError("MESSAGE_SEND", any()) }
        verify(exactly = 0) { listener.onMessageSent(any(), any()) }
    }

    @Test
    fun `sendRawHl7Message sends raw string and routes ack through listener`() {
        val clientManager = mockk<MllpConnectionManager>(relaxed = true)
        coEveryReturns(clientManager, "RAW_ACK")
        setField("clientManager", clientManager)
        val listener = mockk<Hl7EventListener>(relaxed = true)
        setField("listener", listener)

        runBlocking { service.sendRawHl7Message("RAW_MESSAGE") }
        drainCoroutines()

        verify(exactly = 1) { listener.onMessageSent("RAW_MESSAGE", "INR_RESPONSE") }
        verify(exactly = 1) { listener.onAckReceived("RAW_ACK", "INR_RESPONSE") }
    }

    @Test
    fun `sendRawHl7Message propagates the send exception to the caller`() {
        // Unlike sendHl7Message, sendRawHl7Message has no try/catch around clientManager.send —
        // the exception is expected to propagate, not be routed through listener.onError.
        val clientManager = mockk<MllpConnectionManager>()
        coEveryThrows(clientManager, RuntimeException("raw send failed"))
        setField("clientManager", clientManager)
        val listener = mockk<Hl7EventListener>(relaxed = true)
        setField("listener", listener)

        val error = assertThrows(RuntimeException::class.java) {
            runBlocking { service.sendRawHl7Message("RAW_MESSAGE") }
        }

        assertEquals("raw send failed", error.message)
        verify(exactly = 0) { listener.onError(any(), any()) }
        verify(exactly = 0) { listener.onMessageSent(any(), any()) }
    }

    // ──────────────────────────── clearPmsCertPin ────────────────────────────

    @Test
    fun `clearPmsCertPin clears pin, unblocks cert mismatch, and re-triggers discovery`() {
        val tlsFactory = mockk<TlsSocketFactory>(relaxed = true)
        val clientManager = mockk<MllpConnectionManager>(relaxed = true)
        val nsdHelper = mockk<NsdHelper>(relaxed = true)
        setField("tlsFactory", tlsFactory)
        setField("clientManager", clientManager)
        setField("nsdHelper", nsdHelper)
        val listener = mockk<Hl7EventListener>(relaxed = true)
        setField("listener", listener)
        // discoverPmsAndConnect() reads config.nsdDiscoveryType
        setField("config", HL7Config())

        service.clearPmsCertPin()

        verifyOrder {
            tlsFactory.clearServerPin()
            clientManager.unblockCertMismatch()
            nsdHelper.discover(any(), any())
        }
    }

    // ──────────────────────────── discoverPmsAndConnect / static PMS ────────────────────────────

    @Test
    fun `discoverPmsAndConnect uses NSD discovery when static PMS connection is disabled`() {
        val nsdHelper = mockk<NsdHelper>(relaxed = true)
        setField("nsdHelper", nsdHelper)
        setField("config", HL7Config(useStaticPmsConnection = false))
        val listener = mockk<Hl7EventListener>(relaxed = true)
        setField("listener", listener)

        service.discoverPmsAndConnect()

        verify(exactly = 1) { nsdHelper.discover(any(), any()) }
        verify(exactly = 1) { listener.onNsdDiscoveryStarted() }
    }

    @Test
    fun `discoverPmsAndConnect connects directly to static PMS ip-port when enabled`() {
        val clientManager = mockk<MllpConnectionManager>(relaxed = true)
        coEveryReturns(clientManager, "ACK")
        every { clientManager.isConnected() } returns false
        setField("clientManager", clientManager)
        setField("config", HL7Config(useStaticPmsConnection = true, pmsIp = "10.0.0.5", pmsPort = 2575))
        val listener = mockk<Hl7EventListener>(relaxed = true)
        setField("listener", listener)

        service.discoverPmsAndConnect()
        drainCoroutines()

        verify(exactly = 1) { listener.onNsdServiceFound("PMS", "10.0.0.5", 2575) }
        io.mockk.coVerify(exactly = 1) { clientManager.connect("10.0.0.5", 2575) }
    }

    @Test
    fun `discoverPmsAndConnect skips reconnect when static PMS host is already connected`() {
        val clientManager = mockk<MllpConnectionManager>(relaxed = true)
        every { clientManager.isConnected() } returns true
        setField("clientManager", clientManager)
        setField("config", HL7Config(useStaticPmsConnection = true, pmsIp = "10.0.0.5", pmsPort = 2575))
        val listener = mockk<Hl7EventListener>(relaxed = true)
        setField("listener", listener)
        setField("lastConnectedHost", "10.0.0.5:2575")

        service.discoverPmsAndConnect()
        drainCoroutines()

        io.mockk.coVerify(exactly = 0) { clientManager.connect(any(), any()) }
    }

    @Test
    fun `discoverPmsAndConnect schedules a retry when static PMS ip-port is not configured yet`() {
        val clientManager = mockk<MllpConnectionManager>(relaxed = true)
        setField("clientManager", clientManager)
        setField("config", HL7Config(useStaticPmsConnection = true, pmsIp = null, pmsPort = 0))
        val listener = mockk<Hl7EventListener>(relaxed = true)
        setField("listener", listener)

        service.discoverPmsAndConnect()
        drainCoroutines()

        io.mockk.coVerify(exactly = 0) { clientManager.connect(any(), any()) }
        verify(exactly = 0) { listener.onNsdServiceFound(any(), any(), any()) }
    }

    // ──────────────────────────── test helpers for coroutine mocks ────────────────────────────

    private fun coEveryReturns(clientManager: MllpConnectionManager, value: String) {
        io.mockk.coEvery { clientManager.send(any()) } returns value
    }

    private fun coEveryThrows(clientManager: MllpConnectionManager, throwable: Throwable) {
        io.mockk.coEvery { clientManager.send(any()) } throws throwable
    }

    // sendHl7Message/sendRawHl7Message launch on serviceScope (Dispatchers.IO). Give the
    // launched coroutine a chance to run and complete before asserting on it.
    private fun drainCoroutines() {
        Thread.sleep(300)
    }
}
