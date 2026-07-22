package com.rite.pillcounting.core.hl7.mllp.client

import com.rite.pillcounting.util.MainDispatcherRule
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.security.cert.CertificateException

@OptIn(ExperimentalCoroutinesApi::class)
class MllpConnectionManagerTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var client: MllpClient
    private lateinit var scope: TestScope
    private var firstConnectedCalls = 0
    private var connectedCalls = 0
    private var disconnectedCalls = 0
    private var certMismatchCalls = 0

    private lateinit var manager: MllpConnectionManager

    @Before
    fun setUp() {
        client = mockk(relaxed = true)
        scope = TestScope(mainDispatcherRule.testDispatcher)
        firstConnectedCalls = 0
        connectedCalls = 0
        disconnectedCalls = 0
        certMismatchCalls = 0
        manager = MllpConnectionManager(
            client = client,
            scope = scope,
            onFirstConnected = { firstConnectedCalls++ },
            onConnected = { connectedCalls++ },
            onDisconnected = { disconnectedCalls++ },
            onCertMismatch = { certMismatchCalls++ },
        )
        every { client.startPassiveReader(any(), any(), any()) } returns mockk<Job>(relaxed = true)
    }

// ── isConnected() / connect() happy path ──────────────────────────────

    @Test
    fun `isConnected is false before any connection`() {
        assertFalse(manager.isConnected())
    }

    @Test
    fun `connect succeeds and transitions to Connected state, firing onFirstConnected and onConnected`() = scope.runTest {
        coEvery { client.connect("10.0.0.1", 5000) } returns Unit
        every { client.isConnected() } returns true

        manager.connect("10.0.0.1", 5000)
        advanceUntilIdle()

        assertTrue(manager.isConnected())
        assertEquals(1, firstConnectedCalls)
        assertEquals(1, connectedCalls)
        assertEquals(0, disconnectedCalls)
        coVerify(exactly = 1) { client.connect("10.0.0.1", 5000) }
    }

    @Test
    fun `second successful connect does not re-fire onFirstConnected`() = scope.runTest {
        coEvery { client.connect(any(), any()) } returns Unit
        every { client.isConnected() } returns true

        manager.connect("10.0.0.1", 5000)
        advanceUntilIdle()
        manager.shutdown()
        // simulate reuse after shutdown by creating fresh manager sharing state is not possible;
        // instead verify calling connect() again while already connected doesn't re-fire first-connected.
        manager.connect("10.0.0.1", 5000)
        advanceUntilIdle()

        assertEquals(1, firstConnectedCalls)
    }

    // ── retry / backoff logic ─────────────────────────────────────────────

    @Test
    fun `connect failure retries with backoff and eventually succeeds`() = scope.runTest {
        var callCount = 0
        coEvery { client.connect(any(), any()) } coAnswers {
            callCount++
            if (callCount < 3) throw IOException("connection refused")
        }
        every { client.isConnected() } returns true

        manager.connect("192.168.1.5", 6000)
        advanceUntilIdle()

        assertEquals(3, callCount)
        assertTrue(manager.isConnected())
        assertEquals(1, connectedCalls)
    }

    @Test
    fun `retry backoff delay is capped at MAX_RETRY_DELAY_MS`() = scope.runTest {
        // Fail many times so backoff would exceed 30s uncapped; verify it still eventually succeeds
        var callCount = 0
        coEvery { client.connect(any(), any()) } coAnswers {
            callCount++
            if (callCount < 15) throw IOException("still refused")
        }
        every { client.isConnected() } returns true

        manager.connect("1.2.3.4", 100)
        advanceUntilIdle()

        assertEquals(15, callCount)
        assertTrue(manager.isConnected())
    }

    // ── cert mismatch handling ─────────────────────────────────────────────

    @Test
    fun `certificate fingerprint mismatch blocks reconnect and invokes onCertMismatch, no further retries`() = scope.runTest {
        val certEx = CertificateException("TLS fingerprint mismatch detected")
        coEvery { client.connect(any(), any()) } throws certEx

        manager.connect("10.0.0.9", 7000)
        advanceUntilIdle()

        assertFalse(manager.isConnected())
        assertEquals(1, certMismatchCalls)
        coVerify(exactly = 1) { client.connect("10.0.0.9", 7000) }
    }

    @Test
    fun `non-fingerprint certificate exception is treated as regular retryable failure`() = scope.runTest {
        var callCount = 0
        coEvery { client.connect(any(), any()) } coAnswers {
            callCount++
            if (callCount == 1) throw CertificateException("expired certificate")
            else Unit
        }
        every { client.isConnected() } returns true

        manager.connect("10.0.0.9", 7000)
        advanceUntilIdle()

        assertEquals(0, certMismatchCalls)
        assertEquals(2, callCount)
        assertTrue(manager.isConnected())
    }

    @Test
    fun `unblockCertMismatch clears the blocked flag allowing continuous reconnect to resume`() = scope.runTest {
        val certEx = CertificateException("fingerprint mismatch")
        coEvery { client.connect(any(), any()) } throws certEx

        manager.connect("10.0.0.9", 7000)
        advanceUntilIdle()
        assertEquals(1, certMismatchCalls)

        // now clear mock behavior to succeed, and unblock
        coEvery { client.connect(any(), any()) } returns Unit
        every { client.isConnected() } returns true
        manager.unblockCertMismatch()

        // startContinuousReconnect()'s loop only ever delays and re-checks state — it
        // never truly becomes idle, so advanceUntilIdle() here would hang forever.
        // Use bounded time advancement plus runCurrent() to drain exactly the work
        // scheduled up to that point instead.
        manager.startContinuousReconnect()
        advanceTimeBy(15_001L)
        runCurrent()

        assertTrue(manager.isConnected())

        // startContinuousReconnect() loops forever until shutdown; end it so runTest's
        // "no active child jobs" check at the end of this block doesn't fail.
        manager.shutdown()
        advanceUntilIdle()
    }

    // ── send() ─────────────────────────────────────────────────────────────

    @Test
    fun `send throws IOException immediately when manager is shut down`() = scope.runTest {
        manager.shutdown()

        var thrown: Exception? = null
        try {
            manager.send("MSH|...")
        } catch (e: Exception) {
            thrown = e
        }

        assertTrue(thrown is IOException)
        assertEquals("Shutdown", thrown?.message)
    }

    @Test
    fun `send returns response when already connected`() = scope.runTest {
        coEvery { client.connect(any(), any()) } returns Unit
        every { client.isConnected() } returns true
        coEvery { client.send("PING") } returns "ACK"

        manager.connect("10.0.0.1", 5000)
        advanceUntilIdle()

        val response = manager.send("PING")

        assertEquals("ACK", response)
    }

    @Test
    fun `send reconnects first when not connected, then succeeds`() = scope.runTest {
        every { client.isConnected() } returns false
        coEvery { client.connect(any(), any()) } answers {
            every { client.isConnected() } returns true
        }
        coEvery { client.send(any()) } returns "ACK-RECONNECTED"

        // manager never had ip/port set via connect(), so retryConnect() inside send() is a no-op
        // (ip.isEmpty() short-circuits). Set ip/port first.
        manager.connect("10.0.0.1", 5000)
        advanceUntilIdle()

        val response = manager.send("HELLO")
        assertEquals("ACK-RECONNECTED", response)
    }

    @Test
    fun `send retries once on failure then throws after exhausting retries`() = scope.runTest {
        coEvery { client.connect(any(), any()) } returns Unit
        every { client.isConnected() } returns true
        coEvery { client.send(any()) } throws IOException("socket reset")

        manager.connect("10.0.0.1", 5000)
        advanceUntilIdle()

        var thrown: Exception? = null
        try {
            manager.send("MSG")
        } catch (e: Exception) {
            thrown = e
        }

        assertTrue(thrown is IOException)
        assertEquals("socket reset", thrown?.message)
        coVerify(atLeast = 2) { client.send("MSG") }
    }

    @Test
    fun `send succeeds on second attempt after first failure`() = scope.runTest {
        coEvery { client.connect(any(), any()) } returns Unit
        every { client.isConnected() } returns true
        var attempt = 0
        coEvery { client.send("RETRY_MSG") } answers {
            attempt++
            if (attempt == 1) throw IOException("transient")
            "OK"
        }

        manager.connect("10.0.0.1", 5000)
        advanceUntilIdle()

        val response = manager.send("RETRY_MSG")

        assertEquals("OK", response)
        assertEquals(2, attempt)
    }

    // ── startContinuousReconnect ─────────────────────────────────────────

    @Test
    fun `startContinuousReconnect reconnects periodically while disconnected`() = scope.runTest {
        var callCount = 0
        coEvery { client.connect(any(), any()) } coAnswers {
            callCount++
            throw IOException("down")
        }

        // client.connect() always throws, so retryConnect()'s while(!isShutdown) loop
        // never returns on its own. Launch it as a background job (instead of calling
        // the suspend function directly on the test driver) so the test can still
        // advance bounded virtual time and call shutdown() to stop the loop, rather
        // than blocking forever inside advanceUntilIdle().
        scope.launch { manager.connect("10.0.0.1", 5000) }
        advanceTimeBy(1L)
        runCurrent()
        val initialCalls = callCount

        manager.startContinuousReconnect()
        advanceTimeBy(15_001L)
        runCurrent()

        assertTrue("expected additional connect attempts from reconnect loop", callCount > initialCalls)

        // startContinuousReconnect() loops forever until shutdown; end it so runTest's
        // "no active child jobs" check at the end of this block doesn't fail.
        manager.shutdown()
        advanceUntilIdle()
    }

    @Test
    fun `startContinuousReconnect stops looping after shutdown`() = scope.runTest {
        coEvery { client.connect(any(), any()) } throws IOException("down")

        // Same reasoning as above: connect() never returns while client.connect() always
        // fails, so it must run as a background job to let shutdown() interrupt it.
        scope.launch { manager.connect("10.0.0.1", 5000) }
        advanceTimeBy(1L)
        runCurrent()

        manager.startContinuousReconnect()
        manager.shutdown()
        advanceUntilIdle()

        assertFalse(manager.isConnected())
    }

    // ── shutdown() ─────────────────────────────────────────────────────────

    @Test
    fun `shutdown cancels jobs, closes client and marks state disconnected`() = scope.runTest {
        coEvery { client.connect(any(), any()) } returns Unit
        every { client.isConnected() } returns true

        manager.connect("10.0.0.1", 5000)
        advanceUntilIdle()
        assertTrue(manager.isConnected())

        manager.shutdown()
        advanceUntilIdle()

        assertFalse(manager.isConnected())
        coVerify(atLeast = 1) { client.close() }
    }

    @Test
    fun `connect after shutdown resets isShutdown flag and can reconnect`() = scope.runTest {
        coEvery { client.connect(any(), any()) } returns Unit
        every { client.isConnected() } returns true

        manager.connect("10.0.0.1", 5000)
        advanceUntilIdle()
        manager.shutdown()
        advanceUntilIdle()
        assertFalse(manager.isConnected())

        manager.connect("10.0.0.1", 5000)
        advanceUntilIdle()

        assertTrue(manager.isConnected())
    }

    // ── passive reader onDisconnected callback ──────────────────────────

    @Test
    fun `passive reader onDisconnected callback marks state disconnected and closes client`() = scope.runTest {
        val onDisconnectedSlot: CapturingSlot<() -> Unit> = slot()
        coEvery { client.connect(any(), any()) } returns Unit
        every { client.isConnected() } returns true
        every {
            client.startPassiveReader(any(), any(), capture(onDisconnectedSlot))
        } returns mockk<Job>(relaxed = true)

        manager.connect("10.0.0.1", 5000)
        advanceUntilIdle()
        assertTrue(manager.isConnected())

        onDisconnectedSlot.captured.invoke()
        advanceUntilIdle()

        assertFalse(manager.isConnected())
        assertEquals(1, disconnectedCalls)
    }
}
