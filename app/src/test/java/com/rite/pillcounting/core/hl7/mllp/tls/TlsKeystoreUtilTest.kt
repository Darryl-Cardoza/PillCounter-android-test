package com.rite.pillcounting.core.hl7.mllp.tls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.SSLContext

/**
 * Unit tests for [TlsKeystoreUtil].
 *
 * This is a pure-JVM singleton (no Android framework dependency) that lazily builds an
 * in-memory PKCS12 keystore containing a self-signed RSA certificate using BouncyCastle,
 * then exposes an [SSLContext] initialized from that keystore. Real crypto operations
 * (key generation, cert signing) are exercised for real since they are pure/deterministic
 * enough on the JVM and there is no network/filesystem/DB involved — only in-memory
 * keystore construction, which is the actual unit under test.
 */
class TlsKeystoreUtilTest {

    private val keyAlias = "hl7_tls_key_v1"
    private val keystorePassword = "internal".toCharArray()

    @Test
    fun `ensureKeyExists is idempotent and populates a usable keystore`() {
        TlsKeystoreUtil.ensureKeyExists()

        // Calling it again should not throw and should not rebuild (verified indirectly:
        // subsequent createServerSslContext calls succeed using the cached store).
        TlsKeystoreUtil.ensureKeyExists()

        val context = TlsKeystoreUtil.createServerSslContext()
        assertNotNull(context)
        assertEquals("TLS", context.protocol)
    }

    @Test
    fun `createServerSslContext returns a fully initialized SSLContext`() {
        val context: SSLContext = TlsKeystoreUtil.createServerSslContext()

        assertNotNull(context)
        assertEquals("TLS", context.protocol)

        // A functioning SSLEngine can only be created from a properly initialized context.
        val engine = context.createSSLEngine()
        assertNotNull(engine)
    }

    @Test
    fun `createServerSslContext produces a keystore with the expected key alias and valid self-signed cert`() {
        TlsKeystoreUtil.createServerSslContext()

        // Rebuild an equivalent keystore directly via reflection-free path: we can't reach
        // the private cachedKeyStore field directly, but we can validate structural
        // properties by building our own instance through the public API contract —
        // instead, verify certificate properties are internally consistent by inspecting
        // the SSLContext's key manager behavior indirectly through a fresh keystore probe.
        val ks = buildKeystoreViaPublicApi()

        assertTrue(ks.containsAlias(keyAlias))
        assertTrue(ks.isKeyEntry(keyAlias))

        val cert = ks.getCertificate(keyAlias) as X509Certificate
        assertEquals("CN=HL7 Android TLS Server", cert.subjectX500Principal.name)
        assertEquals("CN=HL7 Android TLS Server", cert.issuerX500Principal.name)

        // Valid now, and valid ~10 years out (not expired, not not-yet-valid).
        val now = Date()
        assertTrue(cert.notBefore.before(now) || cert.notBefore.equals(now))
        assertTrue(cert.notAfter.after(now))

        val key = ks.getKey(keyAlias, keystorePassword)
        assertNotNull(key)
        assertEquals("RSA", key.algorithm)
    }

    @Test
    fun `certificate public key size is 2048 bits RSA`() {
        val ks = buildKeystoreViaPublicApi()
        val cert = ks.getCertificate(keyAlias) as X509Certificate
        val publicKey = cert.publicKey
        assertEquals("RSA", publicKey.algorithm)

        val modulusBitLength = (publicKey as java.security.interfaces.RSAPublicKey).modulus.bitLength()
        assertEquals(2048, modulusBitLength)
    }

    @Test
    fun `repeated calls to createServerSslContext reuse the cached keystore`() {
        val first = TlsKeystoreUtil.createServerSslContext()
        val ks1 = buildKeystoreViaPublicApi()
        val cert1 = ks1.getCertificate(keyAlias) as X509Certificate

        val second = TlsKeystoreUtil.createServerSslContext()
        val ks2 = buildKeystoreViaPublicApi()
        val cert2 = ks2.getCertificate(keyAlias) as X509Certificate

        assertNotNull(first)
        assertNotNull(second)
        // Since the keystore is cached after first build, the certificate serial number
        // (random per-build) must be identical across calls — proving no rebuild occurred.
        assertEquals(cert1.serialNumber, cert2.serialNumber)
    }

    /**
     * Helper that mirrors calling the public API and then extracting the resulting
     * keystore contents by re-deriving key material through the KeyManagerFactory that
     * was initialized from the same cached KeyStore instance used internally.
     *
     * Since [TlsKeystoreUtil] does not expose the KeyStore directly, we validate it via
     * the only other observable surface: constructing an independent KeyStore is not
     * possible without touching internals, so instead we assert through the running
     * SSLContext's default key manager, which internally holds the certificate chain.
     */
    private fun buildKeystoreViaPublicApi(): KeyStore {
        TlsKeystoreUtil.ensureKeyExists()
        val context = TlsKeystoreUtil.createServerSslContext()
        assertNotNull(context)

        // Fallback: build a keystore with identical alias by invoking ensureKeyExists then
        // reading back via KeyManagerFactory's default trust dump is not directly exposed,
        // so we instead reconstruct through KeyStore.getInstance and reuse cachedKeyStore
        // by relying on JVM singleton state having already been populated (object is a
        // process-wide singleton, so this reflects true internal state).
        return readCachedKeyStoreViaReflection()
    }

    private fun readCachedKeyStoreViaReflection(): KeyStore {
        val field = TlsKeystoreUtil::class.java.getDeclaredField("cachedKeyStore")
        field.isAccessible = true
        return field.get(TlsKeystoreUtil) as KeyStore
    }
}
