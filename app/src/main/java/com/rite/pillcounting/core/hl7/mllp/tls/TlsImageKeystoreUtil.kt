package com.rite.pillcounting.core.hl7.imageWebService

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
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

// core/hl7/imageWebService/TlsImageKeystoreUtil.kt

object TlsImageKeystoreUtil {

    const val TAG = "TlsImageKeystoreUtil"
    private const val KEY_ALIAS = "image_server_tls"
    private const val KEYSTORE_FILE = "image_server.p12"
    private const val PREFS_NAME = "tls_image_ks_prefs"
    private const val PREF_KEY_PASSWORD = "ks_pw"

    private val logger = AppLogger(TAG)

    // ----------------------------------------------------------------
    // Public API
    // ----------------------------------------------------------------

    fun alias(): String = KEY_ALIAS

    // FIX 1: Password now fetched from EncryptedSharedPreferences
    // instead of the hardcoded "img_tls_internal" constant
    fun password(context: Context): CharArray =
        getOrCreateKeystorePassword(context)

    fun ensureKeystore(context: Context): KeyStore {
        val file = keystoreFile(context)
        val password = getOrCreateKeystorePassword(context)

        return if (file.exists()) {
            // FIX 2: Log.d replaced with AppLogger — respects BuildConfig.DEBUG
            logger.d("Loading existing keystore from disk")
            loadFromDisk(file, password)
        } else {
            logger.d("No keystore found — generating new self-signed cert")
            generateAndSave(file, password)
        }
    }

    fun fingerprint(context: Context): String {
        return try {
            val ks = ensureKeystore(context)
            val cert = ks.getCertificate(KEY_ALIAS)
            MessageDigest.getInstance("SHA-256")
                .digest(cert.encoded)
                .joinToString(":") { "%02X".format(it) }
        } catch (e: Exception) {
            // FIX 2: Log.e replaced — errors always fire, AppLogger.e is correct here
            logger.e("Failed to get fingerprint", e)
            "UNKNOWN"
        }
    }

    // ----------------------------------------------------------------
    // FIX 1: Password management via EncryptedSharedPreferences
    // ----------------------------------------------------------------

    private fun getOrCreateKeystorePassword(context: Context): CharArray {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        val prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        val existing = prefs.getString(PREF_KEY_PASSWORD, null)
        if (existing != null) return existing.toCharArray()

        // Generate a new random 32-byte password, store it encrypted
        val newPassword = ByteArray(32)
            .also { SecureRandom().nextBytes(it) }
            .let { Base64.encodeToString(it, Base64.NO_WRAP) }

        prefs.edit { putString(PREF_KEY_PASSWORD, newPassword) }
        logger.i("Generated new keystore password")
        return newPassword.toCharArray()
    }

    // ----------------------------------------------------------------
    // Private helpers — password passed in rather than read from constant
    // ----------------------------------------------------------------

    private fun keystoreFile(context: Context): File =
        File(context.filesDir, KEYSTORE_FILE)

    private fun loadFromDisk(file: File, password: CharArray): KeyStore {
        val ks = KeyStore.getInstance("PKCS12")
        FileInputStream(file).use { fis ->
            ks.load(fis, password)
        }
        return ks
    }

    private fun generateAndSave(file: File, password: CharArray): KeyStore {
        val keyPairGen = KeyPairGenerator.getInstance("RSA")
        keyPairGen.initialize(2048, SecureRandom())
        val keyPair = keyPairGen.generateKeyPair()

        val now = Date()
        val expiry = Calendar.getInstance()
            .apply { add(Calendar.YEAR, 10) }.time

        val subject = X500Name("CN=PillCounter Image Server")

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

        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(KEY_ALIAS, keyPair.private, password, arrayOf(cert))

        FileOutputStream(file).use { fos ->
            ks.store(fos, password)
        }

        logger.i("New self-signed cert generated and saved")
        return ks
    }
}