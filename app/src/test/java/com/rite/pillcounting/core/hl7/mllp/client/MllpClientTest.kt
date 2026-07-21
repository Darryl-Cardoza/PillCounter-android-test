package com.rite.pillcounting.core.hl7.mllp.client

import com.rite.pillcounting.core.hl7.mllp.tls.TlsSocketFactory
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException

@OptIn(ExperimentalCoroutinesApi::class)
class MllpClientTest {

    private val socketFactory: TlsSocketFactory = mockk()

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun frame(msg: String): ByteArray =
        byteArrayOf(0x0B) + msg.toByteArray() + byteArrayOf(0x1C, 0x0D)

    /** InputStream that returns bytes from [bytes] then throws SocketTimeoutException forever. */
    private class TimeoutAfterInputStream(private val bytes: ByteArray) : InputStream() {
        private var pos = 0
        override fun read(): Int {
            if (pos < bytes.size) return bytes[pos++].toInt() and 0xFF
            throw SocketTimeoutException("no more data")
        }
    }

    private fun fakeSocket(responseBytes: ByteArray, out: ByteArrayOutputStream = ByteArrayOutputStream()): Socket {
        val sock: Socket = mockk(relaxed = true)
        every { sock.isClosed } returns false
        every { sock.isConnected } returns true
        every { sock.inputStream } returns TimeoutAfterInputStream(responseBytes)
        every { sock.outputStream } returns out
        return sock
    }

    @Test
    fun `connect establishes socket and isConnected becomes true`() = runTest {
        val sock = fakeSocket(ByteArray(0))
        every { socketFactory.createSocket("1.2.3.4", 5000) } returns sock

        val client = MllpClient(socketFactory)
        client.connect("1.2.3.4", 5000)

        assertTrue(client.isConnected())
    }

    @Test
    fun `isConnected returns false before any connect`() {
        val client = MllpClient(socketFactory)
        assertFalse(client.isConnected())
    }

    @Test
    fun `isConnected returns false when socket reports closed`() = runTest {
        val sock = fakeSocket(ByteArray(0))
        every { sock.isClosed } returns true
        every { socketFactory.createSocket(any(), any()) } returns sock

        val client = MllpClient(socketFactory)
        client.connect("h", 1)

        assertFalse(client.isConnected())
    }

    @Test
    fun `send throws when not connected`() = runTest {
        val client = MllpClient(socketFactory)

        var threw = false
        try {
            client.send("MSG")
        } catch (e: IllegalArgumentException) {
            threw = true
            assertEquals("Not connected", e.message)
        }
        assertTrue(threw)
    }

    @Test
    fun `send wraps message with MLLP framing and writes to output stream`() = runTest {
        val out = ByteArrayOutputStream()
        val sock = fakeSocket(frame("ACK-OK"), out)
        every { socketFactory.createSocket(any(), any()) } returns sock

        val client = MllpClient(socketFactory)
        client.connect("h", 1)
        val response = client.send("HELLO")

        assertEquals("ACK-OK", response)
        val written = out.toByteArray()
        assertEquals(0x0B, written.first().toInt())
        assertEquals(0x1C, written[written.size - 2].toInt())
        assertEquals(0x0D, written.last().toInt())
        assertEquals("HELLO", String(written, 1, written.size - 3))
    }

    @Test
    fun `readResponse ignores bytes before start byte`() = runTest {
        // Garbage byte before SB should be ignored (started=false branch)
        val garbageThenFrame = byteArrayOf(0x41) + frame("OK")
        val sock = fakeSocket(garbageThenFrame)
        every { socketFactory.createSocket(any(), any()) } returns sock

        val client = MllpClient(socketFactory)
        client.connect("h", 1)
        val response = client.send("X")

        assertEquals("OK", response)
    }

    @Test
    fun `send throws IOException when connection closes mid read`() = runTest {
        // Input stream returns -1 immediately (simulating closed connection) instead of a frame.
        val closedStream = ByteArrayInputStream(ByteArray(0))
        val sock: Socket = mockk(relaxed = true)
        every { sock.isClosed } returns false
        every { sock.isConnected } returns true
        every { sock.inputStream } returns closedStream
        every { sock.outputStream } returns ByteArrayOutputStream()
        every { socketFactory.createSocket(any(), any()) } returns sock

        val client = MllpClient(socketFactory)
        client.connect("h", 1)

        var threw = false
        try {
            client.send("X")
        } catch (e: IOException) {
            threw = true
            assertEquals("Connection closed by server mid-read", e.message)
        }
        assertTrue(threw)
    }

    @Test
    fun `close releases socket and isConnected becomes false`() = runTest {
        val sock = fakeSocket(ByteArray(0))
        every { socketFactory.createSocket(any(), any()) } returns sock

        val client = MllpClient(socketFactory)
        client.connect("h", 1)
        assertTrue(client.isConnected())

        client.close()

        assertFalse(client.isConnected())
    }

    @Test
    fun `connect twice closes previous socket before opening new one`() = runTest {
        val firstSock = fakeSocket(ByteArray(0))
        val secondSock = fakeSocket(ByteArray(0))
        every { socketFactory.createSocket("first", 1) } returns firstSock
        every { socketFactory.createSocket("second", 2) } returns secondSock

        val client = MllpClient(socketFactory)
        client.connect("first", 1)
        client.connect("second", 2)

        assertTrue(client.isConnected())
        io.mockk.verify(exactly = 1) { firstSock.close() }
    }

    @Test
    fun `startPassiveReader invokes onDisconnected when input stream is null`() = runTest {
        val client = MllpClient(socketFactory)
        // Never connected -> input is null
        var disconnectedCalled = false
        val job = client.startPassiveReader(
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
            onMessageReceived = {},
            onDisconnected = { disconnectedCalled = true }
        )
        job.join()

        assertTrue(disconnectedCalled)
    }

    @Test
    fun `startPassiveReader delivers unsolicited message then reports disconnect on stream close`() = runTest {
        val unsolicited = frame("UNSOLICITED-MSG")
        val sock = fakeSocket(ByteArray(0))
        // Override input stream: after the frame bytes, return -1 (clean close) rather than timeout.
        every { sock.inputStream } returns ByteArrayInputStream(unsolicited)
        every { socketFactory.createSocket(any(), any()) } returns sock

        val client = MllpClient(socketFactory)
        client.connect("h", 1)

        val received = mutableListOf<String>()
        var disconnected = false
        val job = client.startPassiveReader(
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
            onMessageReceived = { received.add(it) },
            onDisconnected = { disconnected = true }
        )
        job.join()

        assertEquals(listOf("UNSOLICITED-MSG"), received)
        assertTrue(disconnected)
    }
}
