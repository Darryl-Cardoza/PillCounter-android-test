package com.rite.pillcounting.core.hl7.mllp.tls

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket

/**
 * Unit tests for [TlsProvider].
 *
 * Pure-JVM object with no Android dependency for the trust-all/TOFU context builders,
 * and socket configuration logic exercised against a MockK [SSLSocket]. No real
 * network/filesystem/time/random is used.
 */
class TlsProviderTest {

    @Test
    fun `createTrustAllClientContext returns an initialized TLS context`() {
        val context = TlsProvider.createTrustAllClientContext()

        assertNotNull(context)
        assertEquals("TLS", context.protocol)
        // Only a properly init()-ed context can create an SSLEngine.
        assertNotNull(context.createSSLEngine())
    }

    @Test
    fun `createTrustAllClientContext creates a socket factory usable for TLS sockets`() {
        val context = TlsProvider.createTrustAllClientContext()

        // The trust-all manager must have been accepted by SSLContext.init without throwing,
        // and the resulting factory must be usable to create socket sockets.
        val factory = context.socketFactory
        assertNotNull(factory)
        assertTrue(factory.defaultCipherSuites.isNotEmpty())
    }

    @Test
    fun `createTofuClientContext returns an initialized TLS context using TofuTrustManager`() {
        val mockContext = mockk<Context>(relaxed = true)

        val sslContext = TlsProvider.createTofuClientContext(mockContext, "pms_server")

        assertNotNull(sslContext)
        assertEquals("TLS", sslContext.protocol)
        assertNotNull(sslContext.createSSLEngine())
    }

    @Test
    fun `createTofuClientContext works with different host identifiers producing independent contexts`() {
        val mockContext = mockk<Context>(relaxed = true)

        val context1 = TlsProvider.createTofuClientContext(mockContext, "pms_server_a")
        val context2 = TlsProvider.createTofuClientContext(mockContext, "pms_server_b")

        assertNotNull(context1)
        assertNotNull(context2)
        assertEquals("TLS", context1.protocol)
        assertEquals("TLS", context2.protocol)
    }

    @Test
    fun `configureClientSocket in debug mode enables all supported cipher suites`() {
        val socket = mockk<SSLSocket>(relaxed = true)
        val supportedCiphers = arrayOf("TLS_AES_128_GCM_SHA256", "TLS_RSA_WITH_AES_128_CBC_SHA")
        every { socket.supportedCipherSuites } returns supportedCiphers
        every { socket.sslParameters } returns SSLParameters()

        TlsProvider.configureClientSocket(socket, debugMode = true)

        verify { socket.enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3") }
        verify { socket.enabledCipherSuites = supportedCiphers }
        verify { socket.soTimeout = 30_000 }
    }

    @Test
    fun `configureClientSocket in release mode filters to AES-GCM cipher suites only`() {
        val socket = mockk<SSLSocket>(relaxed = true)
        val supportedCiphers = arrayOf(
            "TLS_AES_128_GCM_SHA256",           // AES + GCM -> kept
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384", // AES + GCM -> kept
            "TLS_RSA_WITH_AES_128_CBC_SHA",      // AES but no GCM -> dropped
            "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305" // neither -> dropped
        )
        every { socket.supportedCipherSuites } returns supportedCiphers
        every { socket.sslParameters } returns SSLParameters()

        val ciphersSlot = slot<Array<String>>()
        TlsProvider.configureClientSocket(socket, debugMode = false)

        verify { socket.enabledCipherSuites = capture(ciphersSlot) }
        val filtered = ciphersSlot.captured
        assertEquals(2, filtered.size)
        assertTrue(filtered.contains("TLS_AES_128_GCM_SHA256"))
        assertTrue(filtered.contains("TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"))
        assertFalse(filtered.contains("TLS_RSA_WITH_AES_128_CBC_SHA"))
        assertFalse(filtered.contains("TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305"))
    }

    @Test
    fun `configureClientSocket release mode with no matching ciphers results in empty array`() {
        val socket = mockk<SSLSocket>(relaxed = true)
        val supportedCiphers = arrayOf("TLS_RSA_WITH_AES_128_CBC_SHA", "TLS_RSA_WITH_3DES_EDE_CBC_SHA")
        every { socket.supportedCipherSuites } returns supportedCiphers
        every { socket.sslParameters } returns SSLParameters()

        val ciphersSlot = slot<Array<String>>()
        TlsProvider.configureClientSocket(socket, debugMode = false)

        verify { socket.enabledCipherSuites = capture(ciphersSlot) }
        assertTrue(ciphersSlot.captured.isEmpty())
    }

    @Test
    fun `configureClientSocket defaults to release mode filtering when debugMode not specified`() {
        val socket = mockk<SSLSocket>(relaxed = true)
        val supportedCiphers = arrayOf("TLS_AES_128_GCM_SHA256", "TLS_RSA_WITH_AES_128_CBC_SHA")
        every { socket.supportedCipherSuites } returns supportedCiphers
        every { socket.sslParameters } returns SSLParameters()

        TlsProvider.configureClientSocket(socket)

        val ciphersSlot = slot<Array<String>>()
        verify { socket.enabledCipherSuites = capture(ciphersSlot) }
        assertEquals(1, ciphersSlot.captured.size)
        assertEquals("TLS_AES_128_GCM_SHA256", ciphersSlot.captured[0])
    }

    @Test
    fun `configureClientSocket disables hostname verification by nulling endpointIdentificationAlgorithm`() {
        val socket = mockk<SSLSocket>(relaxed = true)
        every { socket.supportedCipherSuites } returns emptyArray()
        val params = SSLParameters()
        params.endpointIdentificationAlgorithm = "HTTPS"
        every { socket.sslParameters } returns params

        val paramsSlot = slot<SSLParameters>()
        TlsProvider.configureClientSocket(socket, debugMode = true)

        verify { socket.sslParameters = capture(paramsSlot) }
        assertEquals(null, paramsSlot.captured.endpointIdentificationAlgorithm)
    }

    @Test
    fun `configureClientSocket sets a 30 second read timeout`() {
        val socket = mockk<SSLSocket>(relaxed = true)
        every { socket.supportedCipherSuites } returns emptyArray()
        every { socket.sslParameters } returns SSLParameters()

        TlsProvider.configureClientSocket(socket, debugMode = false)

        verify { socket.soTimeout = 30_000 }
    }
}
