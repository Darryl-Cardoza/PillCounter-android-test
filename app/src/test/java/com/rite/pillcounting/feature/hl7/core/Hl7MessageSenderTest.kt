package com.rite.pillcounting.feature.hl7.core

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.rite.hl7.domain.model.CompleteHL7Message

class Hl7MessageSenderTest {

    private lateinit var serviceManager: Hl7ServiceManager
    private lateinit var sender: Hl7MessageSender

    @Before
    fun setup() {
        serviceManager = mockk(relaxed = true)
        sender = Hl7MessageSender(serviceManager)
    }

    @After
    fun tearDown() {
        io.mockk.unmockkAll()
    }

    @Test
    fun `send delegates to manager sendMessage`() {
        val message = mockk<CompleteHL7Message>()
        every { serviceManager.sendMessage(message) } returns Result.success(Unit)

        sender.send(message)

        verify(exactly = 1) { serviceManager.sendMessage(message) }
    }

    @Test
    fun `sendRaw delegates and returns manager result`() {
        val expected = Result.success(Unit)
        every { serviceManager.sendRawMessage("RAW") } returns expected

        val result = sender.sendRaw("RAW")

        assertEquals(expected, result)
        verify(exactly = 1) { serviceManager.sendRawMessage("RAW") }
    }

    @Test
    fun `sendRaw propagates failure result from manager`() {
        val expected = Result.failure<Unit>(IllegalStateException("boom"))
        every { serviceManager.sendRawMessage("RAW") } returns expected

        val result = sender.sendRaw("RAW")

        assertEquals(expected, result)
    }

    @Test
    fun `connect delegates to manager discoverAndConnect`() {
        justRun { serviceManager.discoverAndConnect() }

        sender.connect()

        verify(exactly = 1) { serviceManager.discoverAndConnect() }
    }
}
