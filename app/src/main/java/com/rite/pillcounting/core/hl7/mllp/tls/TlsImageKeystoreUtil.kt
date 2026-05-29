package com.rite.pillcounting.core.hl7.imageWebService

import android.content.Context
import com.rite.pillcounting.core.utils.logger.AppLogger
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Calendar
import java.util.Date

object TlsImageKeystoreUtil {

    private val logger = AppLogger("TlsImageKeystoreUtil")
    private const val KEY_ALIAS = "image_server_tls"
    private const val KEYSTORE_FILE = "image_server.p12"

    // Internal password — never exposed outside this object
    private const val KEYSTORE_PASSWORD = "img_tls_internal"

    // ----------------------------------------------------------------
    // Public API
    // ----------------------------------------------------------------

    /** Returns alias used when storing the key entry */
    fun alias(): String = KEY_ALIAS

    /** Returns password as CharArray (required by KeyManagerFactory) */
    fun password(): CharArray = KEYSTORE_PASSWORD.toCharArray()

    /**
     * Ensures the PKCS12 keystore file exists on disk.
     * Creates a new self-signed cert if not found.
     * Returns the loaded KeyStore ready for use in SSLContext.
     */
    fun ensureKeystore(context: Context): KeyStore {
        val file = keystoreFile(context)

        return if (file.exists()) {
            logger.d("Loading existing keystore from disk")
            loadFromDisk(file)
        } else {
            logger.d("No keystore found — generating new self-signed cert")
            val ks = generateAndSave(file)
            ks
        }
    }

    /**
     * Returns the SHA-256 fingerprint of the certificate.
     * Clients can use this for trust-on-first-use (TOFU) pinning.
     */
    fun fingerprint(context: Context): String {
        return try {
            val ks = ensureKeystore(context)
            val cert = ks.getCertificate(KEY_ALIAS)
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(cert.encoded)
            digest.joinToString(":") { "%02X".format(it) }
        } catch (e: Exception) {
            logger.e("Failed to get fingerprint", e)
            "UNKNOWN"
        }
    }

    // ----------------------------------------------------------------
    // Private helpers
    // ----------------------------------------------------------------

    private fun keystoreFile(context: Context): File =
        File(context.filesDir, KEYSTORE_FILE)

    private fun loadFromDisk(file: File): KeyStore {
        val ks = KeyStore.getInstance("PKCS12")
        FileInputStream(file).use { fis ->
            ks.load(fis, KEYSTORE_PASSWORD.toCharArray())
        }
        return ks
    }

    private fun generateAndSave(file: File): KeyStore {
        // 1. Generate RSA key pair in software (no AndroidKeyStore)
        val keyPairGen = KeyPairGenerator.getInstance("RSA")
        keyPairGen.initialize(2048, SecureRandom())
        val keyPair = keyPairGen.generateKeyPair()

        // 2. Build self-signed X.509 certificate via BouncyCastle
        val now = Date()
        val expiry = Calendar.getInstance()
            .apply { add(Calendar.YEAR, 10) }.time

        val subject = X500Name("CN=PillCounter Image Server")

        val certBuilder = JcaX509v3CertificateBuilder(
            subject,                                    // issuer (self-signed = same as subject)
            BigInteger.valueOf(SecureRandom().nextLong().coerceAtLeast(1)),
            now,
            expiry,
            subject,                                    // subject
            keyPair.public
        )

        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .build(keyPair.private)

        val cert = JcaX509CertificateConverter()
            .getCertificate(certBuilder.build(signer))

        // 3. Store in PKCS12 keystore
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(
            KEY_ALIAS,
            keyPair.private,
            KEYSTORE_PASSWORD.toCharArray(),
            arrayOf(cert)
        )

        // 4. Persist to disk so cert fingerprint stays stable across restarts
        FileOutputStream(file).use { fos ->
            ks.store(fos, KEYSTORE_PASSWORD.toCharArray())
        }

        logger.i("New self-signed cert generated and saved")
        return ks
    }
}