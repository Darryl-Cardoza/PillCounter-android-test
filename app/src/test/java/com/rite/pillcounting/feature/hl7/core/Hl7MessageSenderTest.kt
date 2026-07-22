package com.rite.pillcounting.feature.hl7.core

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

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
        unmockkAll()
    }

    @Test
    fun `send delegates to manager sendRawMessage`() = runTest {
        coEvery { serviceManager.sendRawMessage("RAW") } returns Result.success("ACK")

        sender.send("RAW")

        coVerify(exactly = 1) { serviceManager.sendRawMessage("RAW") }
    }

    @Test
    fun `sendRaw delegates and returns manager result`() = runTest {
        val expected = Result.success("ACK")
        coEvery { serviceManager.sendRawMessage("RAW") } returns expected

        val result = sender.sendRaw("RAW")

        assertEquals(expected, result)
        coVerify(exactly = 1) { serviceManager.sendRawMessage("RAW") }
    }

    @Test
    fun `sendRaw propagates failure result from manager`() = runTest {
        val expected = Result.failure<String>(IllegalStateException("boom"))
        coEvery { serviceManager.sendRawMessage("RAW") } returns expected

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
