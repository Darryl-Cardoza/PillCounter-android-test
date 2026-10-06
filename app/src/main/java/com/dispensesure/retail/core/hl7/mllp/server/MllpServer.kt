package com.dispensesure.retail.core.hl7.mllp.server

import com.dispensesure.retail.core.hl7.mllp.tls.TlsKeystoreUtil
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.logger.LogEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.rite.hl7.encoding.Mllp
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * MLLP server.
 *
 * Accepts inbound connections from PMS, reads MLLP-framed HL7 messages,
 * invokes [onHl7Message] with the raw text, and writes back the returned ACK
 * string (already wire-formatted by the caller) wrapped in MLLP framing.
 *
 * The server is fully responsible for TCP/MLLP transport only; all HL7 parsing,
 * validation, and ACK building is delegated to the supplied callback so that
 * this class remains decoupled from the AAR library message model.
 *
 * @param port       TCP port to listen on.
 * @param bypassTls  When true, listens on a plain (non-TLS) server socket instead
 *                   of wrapping connections in TLS. Mirrors the app-wide "Bypass TLS"
 *                   preference for pharmacies whose PMS cannot negotiate TLS.
 * @param onHl7Message Suspending callback: receives the raw HL7 text, returns
 *                     the ACK string to echo back (empty string = no reply).
 */
class MllpServer(
    private val port: Int,
    private val bypassTls: Boolean = false,
    private val onHl7Message: suspend (raw: String) -> String
) {

    private val logger = AppLogger("MllpServer")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    private val running = AtomicBoolean(false)
    private val clients = ConcurrentHashMap<String, Socket>()

    private var serverSocket: ServerSocket? = null

    suspend fun start() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (running.get()) return@withContext

            // Bind explicitly to the IPv4 wildcard — some Android network stacks hand back
            // an IPv6-only [::] listener for a bare ServerSocket(port), which silently
            // refuses connections from IPv4-only clients on the same LAN (e.g. a test
            // script) even though the app-side code never sees the attempt.
            val bindAddress = java.net.InetAddress.getByName("0.0.0.0")

            serverSocket = if (bypassTls) {
                ServerSocket(port, 50, bindAddress)
            } else {
                TlsKeystoreUtil.ensureKeyExists()
                val sslContext = TlsKeystoreUtil.createServerSslContext()

                (sslContext.serverSocketFactory.createServerSocket(port, 50, bindAddress) as SSLServerSocket).apply {
                    enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3")
                    enabledCipherSuites = supportedCipherSuites
                    needClientAuth = false
                }
            }

            running.set(true)
            scope.launch { acceptLoop() }
        }
    }

    private fun acceptLoop() {
        while (running.get()) {
            try {
                val socket = serverSocket!!.accept()
                val id = UUID.randomUUID().toString()
                clients[id] = socket

                scope.launch {
                    handleClient(socket, id)
                    clients.remove(id)
                }
            }catch (e: Exception) {
                if (running.get()) {
                    logger.e("acceptLoop() error", e, event = LogEvent.HL7_CONNECT_FAILED)
                }
            }
        }
    }

    private suspend fun handleClient(socket: Socket, id: String) {
        try {
            if (socket is SSLSocket) socket.startHandshake()

            val input = BufferedInputStream(socket.inputStream)
            val output = socket.outputStream

            while (!socket.isClosed) {
                val frame = readMllp(input)
                logger.i("Received MLLP frame (${frame.length} chars) from ${socket.inetAddress?.hostAddress}")
                val messages = splitMessages(frame)
                for (msg in messages) {
                    val ack = try {
                        onHl7Message(msg)
                    } catch (e: Exception) {
                        // Last-resort guard — build a minimal AR ACK so PMS doesn't time out.
                        logger.e("onHl7Message() threw while handling received message — no ACK will be sent", e, event = LogEvent.HL7_RECEIVE_FAILED)
                        val fallbackAck = Hl7FallbackAck.build(msg, e.message ?: "Unknown Error")
                        runCatching { output.write(Mllp.wrap(fallbackAck)); output.flush() }
                        continue
                    }
                    if (ack.isNotEmpty()) {
                        output.write(Mllp.wrap(ack))
                        output.flush()
                    }
                }
            }
        } catch (e: EOFException) {
            logger.d("Client closed connection: ${socket.inetAddress?.hostAddress}")
        } catch (e: Exception) {
            logger.e("handleClient() error for ${socket.inetAddress?.hostAddress}", e, event = LogEvent.HL7_RECEIVE_FAILED)
        } finally {
            socket.close()
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        mutex.withLock {
            running.set(false)
            clients.values.forEach { it.close() }
            clients.clear()
            serverSocket?.close()
            scope.cancel()
        }
    }

    private fun splitMessages(frame: String): List<String> {
        val parts = frame.split(Regex("(?=MSH\\|)")).filter { it.isNotBlank() }
        return if (parts.size > 1) {
            logger.i("Frame contains ${parts.size} concatenated HL7 messages — splitting")
            parts
        } else {
            listOf(frame)
        }
    }

    private fun readMllp(input: InputStream): String {
        val buffer = java.io.ByteArrayOutputStream()
        var started = false

        while (true) {
            val b = input.read()
            if (b == -1) throw EOFException()

            when (b.toByte()) {
                SB -> {
                    started = true
                    buffer.reset()
                }
                EB -> {
                    // Trailing CR after FS is optional depending on sender (some PMS clients
                    // omit it). Only consume it if already buffered — never block waiting for
                    // a byte that may never arrive, or the read stalls until the peer times out.
                    if (input.available() > 0) {
                        input.mark(1)
                        if (input.read() != CR.toInt()) input.reset()
                    }
                    return buffer.toByteArray().decodeToString()
                }
                else -> if (started) buffer.write(b)
            }
        }
    }

    companion object {
        private const val SB: Byte = 0x0B  // Start Block (VT)
        private const val EB: Byte = 0x1C  // End Block (FS)
        private const val CR: Byte = 0x0D  // Carriage Return
    }
}
