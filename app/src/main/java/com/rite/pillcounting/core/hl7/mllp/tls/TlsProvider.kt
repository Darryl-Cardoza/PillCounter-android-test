package com.rite.pillcounting.core.hl7.mllp.tls

import android.content.Context
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.*

object TlsProvider {

    private val TLS_PROTOCOLS = arrayOf("TLSv1.2", "TLSv1.3")

    /**
     * TLS context that encrypts but trusts ANY server certificate.
     */
//    fun createInsecureClientContext(): SSLContext {
//
//        val trustAllManager = object : X509TrustManager {
//            override fun checkClientTrusted(
//                chain: Array<X509Certificate>,
//                authType: String
//            ) = Unit
//
//            override fun checkServerTrusted(
//                chain: Array<X509Certificate>,
//                authType: String
//            ) = Unit
//
//            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
//        }
//
//        return SSLContext.getInstance("TLS").apply {
//            init(
//                null, // no client cert
//                arrayOf<TrustManager>(trustAllManager),
//                SecureRandom()
//            )
//        }
//    }

    /**
     * Creates a TLS context that implements Trust-on-First-Use (TOFU).
     *
     * First connection: accepts the server cert and pins its SHA-256 fingerprint.
     * Subsequent connections: rejects any cert that doesn't match the pinned fingerprint.
     *
     * This replaces the previous trust-all approach while still supporting
     * self-signed certs on the local hospital LAN PMS server.
     */
    fun createTofuClientContext(
        context: Context,
        hostIdentifier: String  // e.g. "pms_server" — used as storage key
    ): SSLContext {
        val tofuManager = TofuTrustManager(context, hostIdentifier)

        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(tofuManager), SecureRandom())
        }
    }

    fun configureClientSocket(socket: SSLSocket) {
        socket.enabledProtocols = TLS_PROTOCOLS

        socket.enabledCipherSuites =
            socket.supportedCipherSuites.filter {
                it.contains("AES") && it.contains("GCM")
            }.toTypedArray()

        // Disable hostname verification (IP-based HL7)
        socket.sslParameters = socket.sslParameters.apply {
            endpointIdentificationAlgorithm = null
        }

        socket.soTimeout = 30_000
    }
}
