package com.rite.pillcounting.core.hl7.mllp.server

import com.rite.pillcounting.core.hl7.mllp.tls.TlsKeystoreUtil
import com.rite.pillcounting.core.utils.logger.AppLogger
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
 * this class remains decoupled from the hl7Core message model.
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
                    logger.e("acceptLoop() error", e)
                }
            }
        }
    }

    private suspend fun handleClient(socket: Socket, id: String) {
        try {
            if (socket is SSLSocket) socket.startHandshake()

            val input = socket.inputStream
            val output = socket.outputStream

            while (!socket.isClosed) {
                val msg = readMllp(input)
                logger.i("Received MLLP message (${msg.length} chars) from ${socket.inetAddress?.hostAddress}")
                val ack = try {
                    onHl7Message(msg)
                } catch (e: Exception) {
                    // A crash inside message handling must not silently kill this client's
                    // read loop with no trace — previously this was swallowed by the outer
                    // catch below, so a message could arrive, fail to parse/process, and
                    // look exactly like "the server never received anything."
                    logger.e("onHl7Message() threw while handling received message — no ACK will be sent", e)
                    throw e
                }
                if (ack.isNotEmpty()) {
                    output.write(Mllp.wrap(ack))
                    output.flush()
                }
            }
        } catch (e: EOFException) {
            logger.d("Client closed connection: ${socket.inetAddress?.hostAddress}")
        } catch (e: Exception) {
            logger.e("handleClient() error for ${socket.inetAddress?.hostAddress}", e)
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
