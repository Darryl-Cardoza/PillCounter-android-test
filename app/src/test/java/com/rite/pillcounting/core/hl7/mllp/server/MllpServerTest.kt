package com.rite.pillcounting.core.hl7.mllp.server

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.rite.hl7.encoding.Mllp
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Exercises MllpServer end-to-end over real localhost TCP sockets with
 * bypassTls = true, avoiding the TLS keystore dependency while still
 * covering the real accept/read/write MLLP framing logic.
 */
class MllpServerTest {

    private fun freePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }

    private fun connectWithRetry(port: Int, attempts: Int = 50): Socket {
        var lastError: Exception? = null
        repeat(attempts) {
            try {
                return Socket("127.0.0.1", port)
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(20)
            }
        }
        throw lastError ?: IllegalStateException("could not connect")
    }

    private fun readMllpFrame(input: java.io.InputStream, timeoutMs: Long = 3000): String {
        val start = System.currentTimeMillis()
        val buffer = ByteArrayOutputStream()
        var started = false
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (input.available() == 0) {
                Thread.sleep(10)
                continue
            }
            val b = input.read()
            if (b == -1) throw java.io.EOFException()
            when (b.toByte()) {
                0x0B.toByte() -> {
                    started = true
                    buffer.reset()
                }
                0x1C.toByte() -> {
                    input.read() // consume CR
                    return buffer.toByteArray().decodeToString()
                }
                else -> if (started) buffer.write(b)
            }
        }
        throw java.io.IOException("timed out waiting for MLLP frame")
    }

    @Test
    fun `server echoes ack for a single framed hl7 message`() = runBlocking {
        val port = freePort()
        val received = CopyOnWriteArrayList<String>()
        val server = MllpServer(port, bypassTls = true) { raw ->
            received.add(raw)
            "ACK-$raw"
        }
        server.start()
        try {
            val socket = connectWithRetry(port)
            socket.getOutputStream().write(Mllp.wrap("MSH|HELLO"))
            socket.getOutputStream().flush()

            val ackFrame = withTimeout(5000) {
                readMllpFrame(socket.getInputStream())
            }

            assertEquals("ACK-MSH|HELLO", ackFrame)
            assertEquals(listOf("MSH|HELLO"), received)
            socket.close()
        } finally {
            server.stop()
        }
    }

    @Test
    fun `server handles multiple sequential messages on same connection`() = runBlocking {
        val port = freePort()
        val received = CopyOnWriteArrayList<String>()
        val server = MllpServer(port, bypassTls = true) { raw ->
            received.add(raw)
            "ACK-$raw"
        }
        server.start()
        try {
            val socket = connectWithRetry(port)
            val out = socket.getOutputStream()
            val input = socket.getInputStream()

            out.write(Mllp.wrap("MSG1"))
            out.flush()
            assertEquals("ACK-MSG1", readMllpFrame(input))

            out.write(Mllp.wrap("MSG2"))
            out.flush()
            assertEquals("ACK-MSG2", readMllpFrame(input))

            assertEquals(listOf("MSG1", "MSG2"), received)
            socket.close()
        } finally {
            server.stop()
        }
    }

    @Test
    fun `server does not write a reply when callback returns empty string`() = runBlocking {
        val port = freePort()
        val server = MllpServer(port, bypassTls = true) { "" }
        server.start()
        try {
            val socket = connectWithRetry(port)
            socket.soTimeout = 500
            socket.getOutputStream().write(Mllp.wrap("NOACK"))
            socket.getOutputStream().flush()

            var timedOut = false
            try {
                socket.getInputStream().read()
            } catch (_: java.net.SocketTimeoutException) {
                timedOut = true
            }
            assertTrue("expected no data to be written back for empty ack", timedOut)
            socket.close()
        } finally {
            server.stop()
        }
    }

    @Test
    fun `server supports multiple concurrent client connections`() = runBlocking {
        val port = freePort()
        val received = CopyOnWriteArrayList<String>()
        val server = MllpServer(port, bypassTls = true) { raw ->
            received.add(raw)
            "ACK-$raw"
        }
        server.start()
        try {
            val socketA = connectWithRetry(port)
            val socketB = connectWithRetry(port)

            socketA.getOutputStream().write(Mllp.wrap("FROM-A"))
            socketA.getOutputStream().flush()
            socketB.getOutputStream().write(Mllp.wrap("FROM-B"))
            socketB.getOutputStream().flush()

            val ackA = readMllpFrame(socketA.getInputStream())
            val ackB = readMllpFrame(socketB.getInputStream())

            assertEquals("ACK-FROM-A", ackA)
            assertEquals("ACK-FROM-B", ackB)
            assertTrue(received.containsAll(listOf("FROM-A", "FROM-B")))

            socketA.close()
            socketB.close()
        } finally {
            server.stop()
        }
    }

    @Test
    fun `start is idempotent when called twice`() = runBlocking {
        val port = freePort()
        val server = MllpServer(port, bypassTls = true) { "ACK" }
        server.start()
        try {
            // Second start should be a no-op (already running) and must not throw
            // or attempt to bind the port again.
            server.start()

            val socket = connectWithRetry(port)
            socket.getOutputStream().write(Mllp.wrap("PING"))
            socket.getOutputStream().flush()
            assertEquals("ACK", readMllpFrame(socket.getInputStream()))
            socket.close()
        } finally {
            server.stop()
        }
    }

    @Test
    fun `stop closes server socket and rejects new connections`() = runBlocking {
        val port = freePort()
        val server = MllpServer(port, bypassTls = true) { "ACK" }
        server.start()
        // Ensure at least one client is connected so stop() also exercises
        // closing tracked client sockets.
        val socket = connectWithRetry(port)
        socket.getOutputStream().write(Mllp.wrap("BEFORE-STOP"))
        socket.getOutputStream().flush()
        readMllpFrame(socket.getInputStream())

        server.stop()

        // give the OS a brief moment to fully release the port
        delay(100)

        var connectFailed = false
        try {
            connectWithRetry(port, attempts = 3)
        } catch (_: Exception) {
            connectFailed = true
        }
        assertTrue("expected new connections to be rejected after stop()", connectFailed)
        socket.close()
    }

    @Test
    fun `stop is safe to call when server was never started`() = runBlocking {
        val port = freePort()
        val server = MllpServer(port, bypassTls = true) { "ACK" }

        // Should not throw even though start() was never invoked.
        server.stop()
    }

    @Test
    fun `malformed frame missing end block does not crash server for other clients`() = runBlocking {
        val port = freePort()
        val server = MllpServer(port, bypassTls = true) { raw -> "ACK-$raw" }
        server.start()
        try {
            // Client 1 sends a start block but abruptly disconnects without EB/CR.
            val badSocket = connectWithRetry(port)
            badSocket.getOutputStream().write(byteArrayOf(0x0B))
            badSocket.getOutputStream().flush()
            badSocket.close()

            // Server must continue accepting other clients despite the bad one.
            val goodSocket = connectWithRetry(port)
            goodSocket.getOutputStream().write(Mllp.wrap("STILL-WORKS"))
            goodSocket.getOutputStream().flush()
            val ack = readMllpFrame(goodSocket.getInputStream())

            assertEquals("ACK-STILL-WORKS", ack)
            goodSocket.close()
        } finally {
            server.stop()
        }
    }

    @Test
    fun `onHl7Message exception is swallowed and connection is closed`() = runBlocking {
        val port = freePort()
        val server = MllpServer(port, bypassTls = true) { throw RuntimeException("boom") }
        server.start()
        try {
            val socket = connectWithRetry(port)
            socket.soTimeout = 2000
            socket.getOutputStream().write(Mllp.wrap("TRIGGER"))
            socket.getOutputStream().flush()

            // Server should close the socket after the callback throws; reading
            // should reach EOF (-1) rather than hang or crash the whole server.
            val result = socket.getInputStream().read()
            assertEquals(-1, result)
            socket.close()
        } finally {
            server.stop()
        }
    }
}
