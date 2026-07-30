package com.rite.pillcounting.core.hl7.mllp.server

import android.util.Log
import com.rite.pillcounting.core.hl7.mllp.tls.TlsKeystoreUtil
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.rite.hl7.encoding.Mllp
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

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    private val running = AtomicBoolean(false)
    private val clients = ConcurrentHashMap<String, Socket>()

    private var serverSocket: ServerSocket? = null

    suspend fun start() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (running.get()) return@withContext

            try {
                serverSocket = if (bypassTls) {
                    ServerSocket(port)
                } else {
                    TlsKeystoreUtil.ensureKeyExists()
                    val sslContext = TlsKeystoreUtil.createServerSslContext()

                    (sslContext.serverSocketFactory.createServerSocket(port) as SSLServerSocket).apply {
                        enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3")
                        enabledCipherSuites = supportedCipherSuites
                        needClientAuth = false
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server failed to start on port $port: ${e.message}", e)
                throw e
            }

            running.set(true)
            Log.i(TAG, "Server started, listening on port $port (bypassTls=$bypassTls)")
            scope.launch { acceptLoop() }
        }
    }

    private suspend fun acceptLoop() {
        while (running.get()) {
            try {
                val socket = serverSocket!!.accept()
                val id = UUID.randomUUID().toString()
                clients[id] = socket
                Log.i(TAG, "Client connected: ${socket.remoteSocketAddress} (id=$id)")

                scope.launch {
                    handleClient(socket, id)
                    clients.remove(id)
                }
            } catch (e: Exception) {
                if (running.get()) {
                    Log.e(TAG, "Accept failed / connection rejected: ${e.message}", e)
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
                val ack = onHl7Message(msg)
                if (ack.isNotEmpty()) {
                    output.write(Mllp.wrap(ack))
                    output.flush()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Client connection closed/errored: ${socket.remoteSocketAddress} (id=$id): ${e.message}")
        } finally {
            socket.close()
            Log.i(TAG, "Client disconnected: ${socket.remoteSocketAddress} (id=$id)")
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        mutex.withLock {
            running.set(false)
            Log.i(TAG, "Server stopping, closing ${clients.size} client connection(s)")
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
                    val raw = buffer.toByteArray().decodeToString()
                    Log.d(TAG, "Raw HL7 received:\n$raw")
                    return raw
                }
                else -> if (started) buffer.write(b)
            }
        }
    }

    companion object {
        private const val TAG = "MllpServer"
        private const val SB: Byte = 0x0B  // Start Block (VT)
        private const val EB: Byte = 0x1C  // End Block (FS)
    }
}
