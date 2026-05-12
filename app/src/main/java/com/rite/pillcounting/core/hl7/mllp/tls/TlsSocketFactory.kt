package com.rite.pillcounting.core.hl7.mllp.tls


import android.content.Context
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * TLS socket factory for HL7 MLLP clients.
 *
 * ✔ Pinned CA
 * ✔ TLS 1.2 / 1.3
 * ✔ Strong ciphers only
 */

// core/hl7/mllp/tls/TlsSocketFactory.kt

class TlsSocketFactory(
    private val context: Context,
    private val hostIdentifier: String = "pms_server"
) {

    private val socketFactory: SSLSocketFactory by lazy {
        TlsProvider.createTofuClientContext(context, hostIdentifier).socketFactory
    }

    fun createSocket(ip: String, port: Int): SSLSocket =
        (socketFactory.createSocket(ip, port) as SSLSocket).apply {
            TlsProvider.configureClientSocket(this)
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
