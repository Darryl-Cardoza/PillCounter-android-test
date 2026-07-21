package com.rite.pillcounting.feature.hl7.core

import android.util.Log
import com.rite.pillcounting.core.hl7.service.HL7Config
import com.rite.pillcounting.core.hl7.service.HL7Service
import com.rite.pillcounting.core.hl7.service.Hl7serviceHandler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.rite.hl7.model.HL7Message

class Hl7ServiceManagerTest {

    private lateinit var serviceHandler: Hl7serviceHandler
    private lateinit var manager: Hl7ServiceManager
    private val config = HL7Config()

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

        serviceHandler = mockk(relaxed = true)
        manager = Hl7ServiceManager(serviceHandler)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ──────────────────────────── initialize ────────────────────────────

    @Test
    fun `initialize sets listener, updates config and starts service when not started`() {
        val eventHandler = mockk<Hl7EventHandler>(relaxed = true)
        every { serviceHandler.isServiceStarted() } returns false

        manager.initialize(config, eventHandler)

        verify(exactly = 1) { serviceHandler.setListener(eventHandler) }
        verify(exactly = 1) { serviceHandler.updateConfig(config) }
        verify(exactly = 1) { serviceHandler.startService() }
        verify(exactly = 0) { serviceHandler.bindService() }
    }

    @Test
    fun `initialize binds service when started but not bound`() {
        val eventHandler = mockk<Hl7EventHandler>(relaxed = true)
        every { serviceHandler.isServiceStarted() } returns true
        every { serviceHandler.isBound() } returns false

        manager.initialize(config, eventHandler)

        verify(exactly = 0) { serviceHandler.startService() }
        verify(exactly = 1) { serviceHandler.bindService() }
    }

    @Test
    fun `initialize does nothing extra when started and bound`() {
        val eventHandler = mockk<Hl7EventHandler>(relaxed = true)
        every { serviceHandler.isServiceStarted() } returns true
        every { serviceHandler.isBound() } returns true

        manager.initialize(config, eventHandler)

        verify(exactly = 0) { serviceHandler.startService() }
        verify(exactly = 0) { serviceHandler.bindService() }
    }

    @Test
    fun `initialize swallows exception from startService path`() {
        val eventHandler = mockk<Hl7EventHandler>(relaxed = true)
        every { serviceHandler.updateConfig(config) } throws RuntimeException("config boom")

        // Should not throw
        manager.initialize(config, eventHandler)

        verify(exactly = 1) { serviceHandler.setListener(eventHandler) }
    }

    // ──────────────────────────── shutdown ────────────────────────────

    @Test
    fun `shutdown stops service successfully`() {
        justRun { serviceHandler.stopService() }

        manager.shutdown()

        verify(exactly = 1) { serviceHandler.stopService() }
    }

    @Test
    fun `shutdown swallows exception from stopService`() {
        every { serviceHandler.stopService() } throws RuntimeException("stop boom")

        // Should not throw
        manager.shutdown()

        verify(exactly = 1) { serviceHandler.stopService() }
    }

    // ──────────────────────────── sendMessage ────────────────────────────

    @Test
    fun `sendMessage fails when service not started`() {
        val message = mockk<HL7Message>()
        every { serviceHandler.isServiceStarted() } returns false

        val result = manager.sendMessage(message)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `sendMessage fails when service not bound`() {
        val message = mockk<HL7Message>()
        every { serviceHandler.isServiceStarted() } returns true
        every { serviceHandler.isBound() } returns false

        val result = manager.sendMessage(message)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `sendMessage fails when getService returns null`() {
        val message = mockk<HL7Message>()
        every { serviceHandler.isServiceStarted() } returns true
        every { serviceHandler.isBound() } returns true
        every { serviceHandler.getService() } returns null

        val result = manager.sendMessage(message)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `sendMessage succeeds and forwards to service`() {
        val message = mockk<HL7Message>()
        val service = mockk<HL7Service>(relaxed = true)
        every { serviceHandler.isServiceStarted() } returns true
        every { serviceHandler.isBound() } returns true
        every { serviceHandler.getService() } returns service

        val result = manager.sendMessage(message)

        assertTrue(result.isSuccess)
        verify(exactly = 1) { service.sendHl7Message(message) }
    }

    @Test
    fun `sendMessage returns failure when service throws`() {
        val message = mockk<HL7Message>()
        val service = mockk<HL7Service>()
        val error = RuntimeException("send boom")
        every { serviceHandler.isServiceStarted() } returns true
        every { serviceHandler.isBound() } returns true
        every { serviceHandler.getService() } returns service
        every { service.sendHl7Message(message) } throws error

        val result = manager.sendMessage(message)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() === error)
    }

    // ──────────────────────────── sendRawMessage ────────────────────────────

    @Test
    fun `sendRawMessage fails when service not started`() = runBlocking {
        every { serviceHandler.isServiceStarted() } returns false

        val result = manager.sendRawMessage("RAW")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `sendRawMessage fails when service not bound`() = runBlocking {
        every { serviceHandler.isServiceStarted() } returns true
        every { serviceHandler.isBound() } returns false

        val result = manager.sendRawMessage("RAW")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `sendRawMessage fails when getService returns null`() = runBlocking {
        every { serviceHandler.isServiceStarted() } returns true
        every { serviceHandler.isBound() } returns true
        every { serviceHandler.getService() } returns null

        val result = manager.sendRawMessage("RAW")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `sendRawMessage succeeds and forwards to service`() = runBlocking {
        val service = mockk<HL7Service>(relaxed = true)
        every { serviceHandler.isServiceStarted() } returns true
        every { serviceHandler.isBound() } returns true
        every { serviceHandler.getService() } returns service
        coEvery { service.sendRawHl7Message("RAW") } returns "ACK"

        val result = manager.sendRawMessage("RAW")

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { service.sendRawHl7Message("RAW") }
    }

    @Test
    fun `sendRawMessage returns failure when service throws`() = runBlocking {
        val service = mockk<HL7Service>()
        val error = RuntimeException("raw boom")
        every { serviceHandler.isServiceStarted() } returns true
        every { serviceHandler.isBound() } returns true
        every { serviceHandler.getService() } returns service
        coEvery { service.sendRawHl7Message("RAW") } throws error

        val result = manager.sendRawMessage("RAW")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() === error)
    }

    // ──────────────────────────── discoverAndConnect ────────────────────────────

    @Test
    fun `discoverAndConnect is no-op when service is null`() {
        every { serviceHandler.getService() } returns null

        manager.discoverAndConnect()

        verify(exactly = 1) { serviceHandler.getService() }
    }

    @Test
    fun `discoverAndConnect delegates when service is present`() {
        val service = mockk<HL7Service>(relaxed = true)
        every { serviceHandler.getService() } returns service

        manager.discoverAndConnect()

        verify(exactly = 1) { service.discoverPmsAndConnect() }
    }

    // ──────────────────────────── clearPmsCertPin ────────────────────────────

    @Test
    fun `clearPmsCertPin clears mismatch and delegates to service when present`() {
        val eventHandler = mockk<Hl7EventHandler>(relaxed = true)
        val service = mockk<HL7Service>(relaxed = true)
        every { serviceHandler.getService() } returns service

        manager.clearPmsCertPin(eventHandler)

        verify(exactly = 1) { eventHandler.clearCertMismatch() }
        verify(exactly = 1) { service.clearPmsCertPin() }
    }

    @Test
    fun `clearPmsCertPin clears mismatch even when service is null`() {
        val eventHandler = mockk<Hl7EventHandler>(relaxed = true)
        every { serviceHandler.getService() } returns null

        manager.clearPmsCertPin(eventHandler)

        verify(exactly = 1) { eventHandler.clearCertMismatch() }
    }

    // ──────────────────────────── updateConfigAndRebroadcast ────────────────────────────

    @Test
    fun `updateConfigAndRebroadcast updates config and rebroadcasts when service present`() {
        val service = mockk<HL7Service>(relaxed = true)
        every { serviceHandler.getService() } returns service

        manager.updateConfigAndRebroadcast(config)

        verify(exactly = 1) { serviceHandler.updateConfig(config) }
        verify(exactly = 1) { service.rebroadcastNsd() }
    }

    @Test
    fun `updateConfigAndRebroadcast updates config when service is null`() {
        every { serviceHandler.getService() } returns null

        manager.updateConfigAndRebroadcast(config)

        verify(exactly = 1) { serviceHandler.updateConfig(config) }
    }
}
