package com.rite.pillcounting.core.hl7.mllp.client

import com.rite.pillcounting.core.hl7.mllp.tls.TlsSocketFactory
import com.rite.pillcounting.core.utils.logger.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException

class MllpClient(
    private val socketFactory: TlsSocketFactory
) {
    private val logger = AppLogger("MllpClient")

    companion object {
        private const val SB: Byte = 0x0B
        private const val EB: Byte = 0x1C
        private const val CR: Byte = 0x0D
        private const val READ_TIMEOUT_MS = 1_000  // non-zero so passiveReader releases streamGate on timeout
        // Large inventory chunks (up to 200 INV/ZIN groups) can take PMS well over 1s to
        // process and ACK. readResponse() must not treat the per-read soTimeout as a hard
        // failure — it should keep polling until this deadline, or the real ACK arrives.
        private const val ACK_WAIT_TIMEOUT_MS = 60_000L
    }

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private val mutex = Mutex()

    // Semaphore with 1 permit acts as a gate on the input stream.
    // send() acquires the permit → passiveReader blocks at acquire()
    // until send() releases it after readResponse() completes.
    // passiveReader then re-enters its blocking read() immediately —
    // no polling, no available(), so TCP close is detected instantly.
    private val streamGate = Semaphore(permits = 1)

    suspend fun connect(ip: String, port: Int) = withContext(Dispatchers.IO) {
        logger.d("connect() — $ip:$port")
        mutex.withLock {
            closeInternal()
            val sock = socketFactory.createSocket(ip, port)
            sock.keepAlive = true
            sock.soTimeout = READ_TIMEOUT_MS
            socket = sock
            input = sock.inputStream
            output = sock.outputStream
            logger.i("connect() — socket established to $ip:$port")
        }
    }

    suspend fun send(message: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(isConnected()) { "Not connected" }
            logger.d("send() — writing ${message.length} chars")

            // Acquire the stream gate — passiveReader will block at its
            // own acquire() call and cannot touch input until we release.
            streamGate.acquire()
            try {
                output?.write(wrap(message))
                output?.flush()
                val response = readResponse()
                logger.d("send() — received response (${response.length} chars)")
                response
            } finally {
                // Always release — even if readResponse() throws —
                // so passiveReader is never permanently frozen.
                streamGate.release()
            }
        }
    }

    fun isConnected(): Boolean =
        socket?.let { !it.isClosed && it.isConnected } ?: false

    fun startPassiveReader(
        scope: CoroutineScope,
        onMessageReceived: (String) -> Unit,
        onDisconnected: () -> Unit
    ): Job {
        // Capture input under mutex before launching coroutine to avoid a race where
        // a concurrent connect() call invokes closeInternal() and nulls the stream
        // between when we succeed here and when the coroutine body actually runs.
        val capturedInput = input
        return scope.launch(Dispatchers.IO) {
        logger.d("startPassiveReader() — started")
        try {
            val stream = capturedInput ?: run {
                logger.w("startPassiveReader() — input stream is null")
                onDisconnected()
                return@launch
            }
            val buffer = ByteArrayOutputStream()
            var started = false

            while (true) {
                // Wait until send() is not holding the stream.
                // This is a real suspend — no spin loop, no polling.
                // withPermit acquires, runs the block, releases.
                streamGate.withPermit {
                    try {
                        val b = stream.read()

                        if (b == -1) {
                            logger.i("startPassiveReader() — clean TCP close (read = -1)")
                            onDisconnected()
                            return@launch
                        }

                        when (b.toByte()) {
                            SB -> { started = true; buffer.reset() }
                            EB -> {
                                stream.read() // consume trailing CR
                                if (started) {
                                    val msg = buffer.toString(Charsets.UTF_8.name())
                                    logger.d("passiveReader — unsolicited msg (${msg.length} chars)")
                                    onMessageReceived(msg)
                                }
                                started = false
                            }
                            else -> if (started) buffer.write(b)
                        }
                    } catch (_: SocketTimeoutException) {
                        // soTimeout expired; release gate so send() can proceed
                    }
                }
            }
        } catch (e: Exception) {
            logger.w("startPassiveReader() — exception: ${e.message}")
            onDisconnected()
        }
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        logger.d("close() — closing socket")
        mutex.withLock { closeInternal() }
    }

    private fun wrap(msg: String): ByteArray =
        byteArrayOf(SB) + msg.toByteArray() + byteArrayOf(EB, CR)

    private fun readResponse(): String {
        val buffer = ByteArrayOutputStream()
        var started = false
        val deadline = System.currentTimeMillis() + ACK_WAIT_TIMEOUT_MS
        while (true) {
            val b = try {
                input!!.read()
            } catch (e: SocketTimeoutException) {
                // soTimeout (1s) firing here only means "no bytes yet" — not a dead
                // connection. Keep waiting for the ACK up to ACK_WAIT_TIMEOUT_MS instead
                // of letting this propagate as a send failure that tears down the socket.
                if (System.currentTimeMillis() >= deadline) {
                    throw IOException("Timed out waiting for ACK after ${ACK_WAIT_TIMEOUT_MS}ms")
                }
                continue
            }
            if (b == -1) throw IOException("Connection closed by server mid-read")
            when (b.toByte()) {
                SB -> { started = true; buffer.reset() }
                EB -> {
                    input!!.read() // consume trailing CR
                    return buffer.toString(Charsets.UTF_8.name())
                }
                else -> if (started) buffer.write(b)
            }
        }
    }

    private fun closeInternal() {
        logger.d("closeInternal() — releasing streams and socket")
        try { input?.close() } catch (_: Exception) {}
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        input = null; output = null; socket = null
    }
}