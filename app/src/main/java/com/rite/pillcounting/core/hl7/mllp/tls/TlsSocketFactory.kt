package com.rite.pillcounting.core.hl7.mllp.tls


import android.content.Context
import com.rite.pillcounting.BuildConfig
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * TLS socket factory for HL7 MLLP clients.
 *
 * Debug builds: trust-all (supports local PMS without a CA-signed cert).
 * Release builds: TOFU pinning (cert fingerprint stored on first connection,
 *                 mismatch throws on subsequent connections).
 *
 * ✔ TLS 1.2 / 1.3
 * ✔ AES-GCM ciphers only
 */
class TlsSocketFactory(
    private val context: Context,
    private val hostIdentifier: String = "pms_server"
) {

    private val socketFactory: SSLSocketFactory by lazy {
        if (BuildConfig.DEBUG) {
            TlsProvider.createTrustAllClientContext().socketFactory
        } else {
            TlsProvider.createTofuClientContext(context, hostIdentifier).socketFactory
        }
    }

    fun createSocket(ip: String, port: Int): SSLSocket =
        (socketFactory.createSocket(ip, port) as SSLSocket).apply {
            TlsProvider.configureClientSocket(this, debugMode = BuildConfig.DEBUG)
            startHandshake()
        }

    /**
     * Call this when the PMS server certificate is intentionally rotated.
     * Forces re-pinning on the next connection attempt.
     */
    fun clearServerPin() {
        TofuTrustManager(context, hostIdentifier).clearPin()
    }

    /**
     * Returns the currently pinned cert fingerprint.
     * Useful for displaying in an admin/settings screen for verification.
     */
    fun pinnedFingerprint(): String? {
        return TofuTrustManager(context, hostIdentifier).currentPin()
    }
}
