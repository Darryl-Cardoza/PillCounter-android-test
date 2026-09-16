package com.rite.pillcounting.core.hl7.mllp.tls

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Calendar
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

object TlsKeystoreUtil {

    private const val KEY_ALIAS = "hl7_tls_key_v1"
    private const val KEYSTORE_PASSWORD = "internal"

    // Held in memory — regenerated each app launch (fine for a local MLLP server)
    @Volatile
    private var cachedKeyStore: KeyStore? = null

    fun ensureKeyExists() {
        if (cachedKeyStore != null) return
        cachedKeyStore = buildSoftwareKeyStore()
    }

    fun createServerSslContext(): SSLContext {
        val keyStore = cachedKeyStore ?: buildSoftwareKeyStore().also { cachedKeyStore = it }

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, KEYSTORE_PASSWORD.toCharArray())

        return SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, null, SecureRandom())
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun buildSoftwareKeyStore(): KeyStore {
        // 1. Generate RSA key pair purely in software (no AndroidKeyStore)
        val keyPairGen = KeyPairGenerator.getInstance("RSA")
        keyPairGen.initialize(2048, SecureRandom())
        val keyPair = keyPairGen.generateKeyPair()

        // 2. Build a self-signed X.509 certificate with BouncyCastle
        val now = Date()
        val expiry = Calendar.getInstance().apply { add(Calendar.YEAR, 10) }.time

        val subject = X500Name("CN=HL7 Android TLS Server")
        val certBuilder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(SecureRandom().nextLong().coerceAtLeast(1)),
            now,
            expiry,
            subject,
            keyPair.public
        )

        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .build(keyPair.private)

        val cert = JcaX509CertificateConverter()
            .getCertificate(certBuilder.build(signer))

        // 3. Store in a plain PKCS12 keystore (lives only in memory)
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(
            KEY_ALIAS,
            keyPair.private,
            KEYSTORE_PASSWORD.toCharArray(),
            arrayOf(cert)
        )
        return ks
    }
}
