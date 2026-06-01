package com.rite.pillcounting.core.hl7.mllp.server

import com.rite.pillcounting.core.hl7.mllp.tls.TlsKeystoreUtil
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.rite.hl7.AckDecision
import org.rite.hl7.AckGenerator
import org.rite.hl7.MshFields
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

class MllpServer(
    private val port: Int,
    private val onHl7Message: suspend (String) -> AckDecision
) {

    companion object {
        private const val SB: Byte = 0x0B
        private const val EB: Byte = 0x1C
        private const val CR: Byte = 0x0D
    }

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
                val msh = extractMshFields(msg)
                val decision = onHl7Message(msg)
                val ack = AckGenerator.generate(msh, decision)

                output.write(wrapMllp(ack))
                output.flush()
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
        val buffer = ByteArrayOutputStream()
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
                    input.read() // CR
                    return buffer.toString(Charsets.UTF_8.name())
                }
                else -> if (started) buffer.write(b)
            }
        }
    }

    private fun wrapMllp(msg: String): ByteArray =
        byteArrayOf(SB) + msg.toByteArray() + byteArrayOf(EB, CR)

    private fun extractMshFields(msg: String): MshFields {
        val msh = msg.lineSequence().first { it.startsWith("MSH|") }
        val f = msh.split("|")

        return MshFields(
            sendingApp = f.getOrElse(2) { "" },
            sendingFacility = f.getOrElse(4) { "" },
            receivingApp = f.getOrElse(3) { "" },
            receivingFacility = f.getOrElse(5) { "" },
            messageControlId = f.getOrElse(9) { UUID.randomUUID().toString() },
            version = f.getOrElse(11) { "2.5" }
        )
    }
}
