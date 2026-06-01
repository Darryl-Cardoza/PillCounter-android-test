package com.rite.pillcounting.core.hl7.mllp.tls

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import com.rite.pillcounting.core.utils.logger.AppLogger
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
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// core/hl7/imageWebService/TlsImageKeystoreUtil.kt

object TlsImageKeystoreUtil {

    private val logger = AppLogger("TlsImageKeystoreUtil")
    private const val KEY_ALIAS = "image_server_tls"
    private const val KEYSTORE_FILE = "image_server.p12"
//    private const val PREFS_NAME = "tls_image_ks_prefs"
//    private const val PREF_KEY_PASSWORD = "ks_pw"

    private val logger = AppLogger(TAG)

    // ----------------------------------------------------------------
    // Public API
    // ----------------------------------------------------------------

    // Password fetched from Keystore
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
            logger.e("Failed to get fingerprint", e)
            "UNKNOWN"
        }
    }

    // ----------------------------------------------------------------
    // FIX 1: Password management via EncryptedSharedPreferences
    // ----------------------------------------------------------------

    private fun getOrCreateKeystorePassword(context: Context): CharArray {
        val keystore = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        val alias = "com.rite.pillcounting.tls_image_ks_pw"
        val prefs = context.getSharedPreferences("tls_image_ks_prefs", Context.MODE_PRIVATE)
        val prefKey = "ks_pw_encrypted"

        // Return existing password if already stored
        val existing = prefs.getString(prefKey, null)
        if (existing != null) {
            return decrypt(existing, keystore, alias).toCharArray()
        }

        // Generate new random password
        val newPassword = Base64.encodeToString(
            ByteArray(32).also { SecureRandom().nextBytes(it) },
            Base64.NO_WRAP
        )

        // Encrypt and store it
        val encrypted = encrypt(newPassword, keystore, alias)
        prefs.edit { putString(prefKey, encrypted) }
        logger.i("Generated new keystore password")
        return newPassword.toCharArray()
    }

    private fun getOrCreateEncryptionKey(
        keystore: KeyStore,
        alias: String
    ): SecretKey {
        keystore.getKey(alias, null)?.let { return it as SecretKey }

        KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        ).apply {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
        }.generateKey()

        return keystore.getKey(alias, null) as SecretKey
    }

    private fun encrypt(value: String, keystore: KeyStore, alias: String): String {
        val key = getOrCreateEncryptionKey(keystore, alias)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        // Store as base64(iv):base64(ciphertext)
        return "${Base64.encodeToString(iv, Base64.NO_WRAP)}:${
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        }"
    }

    private fun decrypt(stored: String, keystore: KeyStore, alias: String): String {
        val parts = stored.split(":")
        require(parts.size == 2) { "Invalid stored password format" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
        val key = getOrCreateEncryptionKey(keystore, alias)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
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