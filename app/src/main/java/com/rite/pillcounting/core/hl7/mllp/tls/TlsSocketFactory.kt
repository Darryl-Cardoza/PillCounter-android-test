package com.rite.pillcounting.core.hl7.mllp.tls


import android.content.Context
import com.rite.pillcounting.BuildConfig
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import java.net.Socket
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
    private val hostIdentifier: String = "pms_server"
) {

    private val socketFactory: SSLSocketFactory by lazy {
        if (BuildConfig.DEBUG) {
            TlsProvider.createTrustAllClientContext().socketFactory
        } else {
            TlsProvider.createTofuClientContext(context, hostIdentifier).socketFactory
        }
    }

    fun createSocket(ip: String, port: Int): Socket {
        if (PreferenceHelper(context).isBypassTlsEnabled()) {
            return Socket(ip, port)
        }

        return (socketFactory.createSocket(ip, port) as SSLSocket).apply {
            TlsProvider.configureClientSocket(this, debugMode = BuildConfig.DEBUG)
            startHandshake()
        }
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
