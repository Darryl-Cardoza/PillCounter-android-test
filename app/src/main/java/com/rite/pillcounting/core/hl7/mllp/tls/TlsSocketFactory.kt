package com.rite.pillcounting.core.hl7.mllp.tls


import android.content.Context
import com.rite.pillcounting.BuildConfig
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Socket factory for HL7 MLLP clients.
 *
 * Debug builds: trust-all TLS (supports local PMS without a CA-signed cert).
 * Release builds: TOFU pinning TLS (cert fingerprint stored on first connection,
 *                 mismatch throws on subsequent connections).
 * Bypass TLS preference: skips TLS entirely and connects with a plain TCP
 *                 socket, for pharmacies whose PMS cannot negotiate TLS at all.
 *
 * ✔ TLS 1.2 / 1.3
 * ✔ AES-GCM ciphers only
 */
class TlsSocketFactory(
    private val context: Context,
    hostIdentifier: String = LEGACY_HOST_IDENTIFIER
) {

    /**
     * Which PMS we are about to talk to. The TOFU pin is keyed off this, so switching to a
     * different PMS no longer collides with the previous one's pin. Set from discovery before
     * each connect; seeded from the constructor argument.
     */
    @Volatile
    var peerIdentifier: String = hostIdentifier

    // One lazily-built context per peer. A single `by lazy` would pin the identifier at first
    // use, and the peer is only known after discovery resolves.
    private val socketFactories = ConcurrentHashMap<String, SSLSocketFactory>()

    private fun socketFactoryFor(identifier: String): SSLSocketFactory =
        socketFactories.getOrPut(identifier) {
            if (BuildConfig.DEBUG) {
                TlsProvider.createTrustAllClientContext().socketFactory
            } else {
                TlsProvider.createTofuClientContext(context, identifier).socketFactory
            }
        }

    fun createSocket(ip: String, port: Int): Socket {
        if (PreferenceHelper(context).isBypassTlsEnabled()) {
            return Socket(ip, port)
        }

        val sslSocket = socketFactoryFor(peerIdentifier).createSocket(ip, port) as SSLSocket
        return try {
            TlsProvider.configureClientSocket(sslSocket, debugMode = BuildConfig.DEBUG)
            sslSocket.startHandshake()
            sslSocket
        } catch (e: Exception) {
            sslSocket.close()
            throw e
        }
    }

    /**
     * Call this when the PMS server certificate is intentionally rotated.
     * Forces re-pinning on the next connection attempt.
     */
    fun clearServerPin() {
        TofuTrustManager(context, peerIdentifier).clearPin()
    }

    /**
     * Returns the currently pinned cert fingerprint.
     * Useful for displaying in an admin/settings screen for verification.
     */
    fun pinnedFingerprint(): String? {
        return TofuTrustManager(context, peerIdentifier).currentPin()
    }

    companion object {
        /** The single identifier used before pins were keyed per peer. */
        const val LEGACY_HOST_IDENTIFIER = "pms_server"
    }
}
