package com.rite.pillcounting.core.hl7.mllp.client

import com.rite.pillcounting.core.utils.logger.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.security.cert.CertificateException

/**
 * High-level MLLP connection manager.
 *
 * Adds:
 * - Retry logic
 * - Auto reconnect
 * - Safe shutdown
 * - Connection state tracking
 */


class MllpConnectionManager(
    private val client: MllpClient,
    private val scope: CoroutineScope,
    private val onFirstConnected: (() -> Unit)? = null,
    private val onConnected: (() -> Unit)? = null,
    private val onDisconnected: (() -> Unit)? = null,
    private val onCertMismatch: (() -> Unit)? = null,
) {
    private val logger = AppLogger("MllpConnectionManager")

    companion object {
        private const val SEND_RETRIES = 2
        private const val RETRY_DELAY_MS = 3_000L
        private const val MAX_RETRY_DELAY_MS = 30_000L  // cap backoff at 30s
        private const val RECONNECT_CHECK_MS = 15_000L
    }

    private val mutex = Mutex()
    private var ip: String = ""
    private var port: Int = 0
    private var isShutdown = false
    private var hasEverConnected = false

    @Volatile private var certMismatchBlocked = false

    private var readerJob: Job? = null       // passive reader — detects disconnect
    private var reconnectJob: Job? = null

    @Volatile
    private var state: ConnectionState = ConnectionState.Disconnected

    fun isConnected(): Boolean = state == ConnectionState.Connected

    suspend fun connect(ip: String, port: Int) {
        logger.d("connect() called — ip=$ip port=$port")
        mutex.withLock {
            this.ip = ip
            this.port = port
            isShutdown = false
        }
        retryConnect()
    }

    suspend fun send(message: String): String {
        if (isShutdown) throw IOException("Shutdown")
        repeat(SEND_RETRIES) { attempt ->
            try {
                if (!isConnected()) {
                    logger.w("send() attempt $attempt — not connected, retrying connect")
                    retryConnect()
                }
                logger.d("send() attempt $attempt — sending message")
                val response = client.send(message)
                logger.d("send() attempt $attempt — received response")
                return response
            } catch (e: Exception) {
                logger.e("send() attempt $attempt failed: ${e.message}", e)
                handleSendFailure()
                if (attempt == SEND_RETRIES - 1) throw e
                retryConnect()
            }
        }
        error("Unreachable")
    }

    fun startContinuousReconnect() {
        logger.d("startContinuousReconnect() called")
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            while (!isShutdown) {
                if (!certMismatchBlocked && state == ConnectionState.Disconnected && ip.isNotEmpty()) {
                    logger.d("reconnect loop — disconnected, attempting retryConnect()")
                    try { retryConnect() } catch (e: Exception) {
                        logger.e("reconnect loop — retryConnect() threw: ${e.message}", e)
                    }
                }
                delay(RECONNECT_CHECK_MS)
            }
            logger.d("reconnect loop — exiting (shutdown)")
        }
    }

    fun unblockCertMismatch() {
        certMismatchBlocked = false
    }

    fun shutdown() {
        logger.d("shutdown() called")
        isShutdown = true
        readerJob?.cancel()
        reconnectJob?.cancel()
        scope.launch { client.close() }
        updateState(ConnectionState.Disconnected)
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private fun updateState(newState: ConnectionState) {
        if (state == newState) return
        logger.i("state: $state → $newState")
        state = newState
        when (newState) {
            ConnectionState.Connected -> onConnected?.invoke()
            ConnectionState.Disconnected -> onDisconnected?.invoke()
            else -> {}
        }
    }

    private suspend fun retryConnect() {
        if (isShutdown || ip.isEmpty()) return
        logger.d("retryConnect() — connecting to $ip:$port")
        updateState(ConnectionState.Connecting)

        var attempt = 0
        while (!isShutdown) {
            try {
                client.connect(ip, port)
                logger.i("retryConnect() — connected to $ip:$port after $attempt attempt(s)")
                onConnectionEstablished()
                return  // success
            } catch (e: Exception) {
                if (isCertMismatch(e)) {
                    logger.e("retryConnect() — PMS certificate mismatch. Blocking reconnects until pin is cleared.")
                    certMismatchBlocked = true
                    updateState(ConnectionState.Disconnected)
                    onCertMismatch?.invoke()
                    return
                }
                attempt++
                val delay = minOf(RETRY_DELAY_MS * attempt, MAX_RETRY_DELAY_MS)
                logger.w("retryConnect() — attempt $attempt failed: ${e.message}. Retrying in ${delay}ms")
                updateState(ConnectionState.Disconnected)
                delay(delay)
            }
        }
    }

    private fun isCertMismatch(e: Exception): Boolean =
        generateSequence<Throwable>(e) { it.cause }
            .any { it is CertificateException && it.message?.contains("fingerprint mismatch") == true }

    private fun onConnectionEstablished() {
        updateState(ConnectionState.Connected)

        if (!hasEverConnected) {
            hasEverConnected = true
            logger.i("onConnectionEstablished() — first-ever connection")
            onFirstConnected?.invoke()
        }

        readerJob?.cancel()

        readerJob = client.startPassiveReader(
            scope = scope,
            onMessageReceived = {
                logger.d("passive reader — unsolicited message received")
            },
            onDisconnected = {
                if (!isShutdown) {
                    logger.w("passive reader — remote disconnected")
                    updateState(ConnectionState.Disconnected)
                    scope.launch { client.close() }
                }
            }
        )
    }

    private fun handleSendFailure() {
        logger.w("handleSendFailure() — marking disconnected and closing client")
        updateState(ConnectionState.Disconnected)
        readerJob?.cancel()
        scope.launch { client.close() }
    }
}
