package com.rite.pillcounting.core.hl7.mllp.server

import com.rite.pillcounting.core.hl7.mllp.tls.TlsKeystoreUtil
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.rite.hl7.encoding.Mllp
import java.io.EOFException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * MLLP/TLS server.
 *
 * Accepts inbound TLS connections from PMS, reads MLLP-framed HL7 messages,
 * invokes [onHl7Message] with the raw text, and writes back the returned ACK
 * string (already wire-formatted by the caller) wrapped in MLLP framing.
 *
 * The server is fully responsible for TCP/MLLP transport only; all HL7 parsing,
 * validation, and ACK building is delegated to the supplied callback so that
 * this class remains decoupled from the hl7Core message model.
 *
 * @param port         TCP port to listen on.
 * @param onHl7Message Suspending callback: receives the raw HL7 text, returns
 *                     the ACK string to echo back (empty string = no reply).
 */
class MllpServer(
    private val port: Int,
    private val onHl7Message: suspend (raw: String) -> String
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    private val running = AtomicBoolean(false)
    private val clients = ConcurrentHashMap<String, SSLSocket>()

    private var serverSocket: SSLServerSocket? = null

    suspend fun start() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (running.get()) return@withContext

            TlsKeystoreUtil.ensureKeyExists()
            val sslContext = TlsKeystoreUtil.createServerSslContext()

            serverSocket = sslContext.serverSocketFactory
                .createServerSocket(port) as SSLServerSocket

            serverSocket!!.apply {
                enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3")
                enabledCipherSuites = supportedCipherSuites
                needClientAuth = false
            }

            running.set(true)
            scope.launch { acceptLoop() }
        }
    }

    private suspend fun acceptLoop() {
        while (running.get()) {
            try {
                val socket = serverSocket!!.accept() as SSLSocket
                val id = UUID.randomUUID().toString()
                clients[id] = socket

                scope.launch {
                    handleClient(socket)
                    clients.remove(id)
                }
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun handleClient(socket: SSLSocket) {
        try {
            socket.startHandshake()

            val input = socket.inputStream
            val output = socket.outputStream

            while (!socket.isClosed) {
                val msg = readMllp(input)
                val ack = onHl7Message(msg)
                if (ack.isNotEmpty()) {
                    output.write(Mllp.wrap(ack))
                    output.flush()
                }
            }
        } catch (_: Exception) {
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
                    input.read() // consume trailing CR
                    return buffer.toByteArray().decodeToString()
                }
                else -> if (started) buffer.write(b)
            }
        }
    }

    companion object {
        private const val SB: Byte = 0x0B  // Start Block (VT)
        private const val EB: Byte = 0x1C  // End Block (FS)
    }
}
