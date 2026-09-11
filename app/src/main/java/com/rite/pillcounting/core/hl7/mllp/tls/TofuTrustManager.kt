package com.rite.pillcounting.core.hl7.mllp.tls

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.rite.pillcounting.core.utils.logger.AppLogger
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import androidx.core.content.edit

@Suppress("CustomX509TrustManager")
class TofuTrustManager(
    private val context: Context,
    private val hostIdentifier: String
) : X509TrustManager {

    private val pinKey = "pin_${hostIdentifier}"
    private val keystoreAlias = "com.rite.pillcounting.tofu_key"

    private val logger = AppLogger.create<TofuTrustManager>()

    init {
        migrateLegacyPin()
    }

    /**
     * Moves a pin stored under the old single-peer key onto this peer's key, once.
     * Without it every upgraded terminal re-does first-contact TOFU, which is a window where
     * anything answering on the PMS port would be trusted and pinned.
     */
    private fun migrateLegacyPin() {
        if (pinKey == LEGACY_PIN_KEY) return
        if (plainPrefs.contains(pinKey)) return
        val legacy = plainPrefs.getString(LEGACY_PIN_KEY, null) ?: return
        // The stored value is already encrypted — move the blob, don't decrypt and re-encrypt.
        plainPrefs.edit {
            putString(pinKey, legacy)
            remove(LEGACY_PIN_KEY)
        }
        logger.block(
            "HL7-NSD · Migrated legacy TOFU pin to a per-peer key",
            "From" to LEGACY_PIN_KEY,
            "To" to pinKey,
        )
    }

    private val systemTrustManager: X509TrustManager by lazy {
        val factory = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm()
        )
        factory.init(null as KeyStore?)
        factory.trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()
    }

    // Plain SharedPreferences — values encrypted manually via Keystore
    private val plainPrefs: SharedPreferences by lazy {
        context.getSharedPreferences("tofu_pins", Context.MODE_PRIVATE)
    }

    private fun getOrCreateKey(): SecretKey {
        val keystore = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        keystore.getKey(keystoreAlias, null)?.let { return it as SecretKey }

        KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        ).apply {
            init(
                KeyGenParameterSpec.Builder(
                    keystoreAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
        }.generateKey()

        return KeyStore.getInstance("AndroidKeyStore")
            .also { it.load(null) }
            .getKey(keystoreAlias, null) as SecretKey
    }

    private fun encrypt(value: String): String {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return "${Base64.encodeToString(iv, Base64.NO_WRAP)}:${
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        }"
    }

    private fun decrypt(stored: String): String {
        val parts = stored.split(":")
        require(parts.size == 2) { "Invalid pin format" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun getPin(): String? {
        val stored = plainPrefs.getString(pinKey, null) ?: return null
        return try { decrypt(stored) } catch (e: Exception) { null }
    }

    private fun savePin(pin: String) {
        plainPrefs.edit { putString(pinKey, encrypt(pin)) }
    }

    // ── X509TrustManager ─────────────────────────────────────────────

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        systemTrustManager.checkClientTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        if (chain.isEmpty()) throw CertificateException("Empty certificate chain")

        val serverCert = chain[0]
        val incomingPin = computePin(serverCert)
        val storedPin = getPin()

        when (storedPin) {
            null -> {
                logger.block(
                    "HL7-NSD · TOFU first contact — pinning this certificate",
                    "Pin key" to pinKey,
                    "Peer" to hostIdentifier,
                    "Fingerprint" to incomingPin.take(23),
                )
                runCatching {
                    systemTrustManager.checkServerTrusted(chain, authType)
                    savePin(incomingPin)
                }.onFailure {
                    savePin(incomingPin)
                }
            }
            incomingPin -> {
                logger.w("HL7-NSD · TOFU pin matched for $pinKey")
                runCatching { systemTrustManager.checkServerTrusted(chain, authType) }
            }
            else -> {
                logger.block(
                    "HL7-NSD · TOFU pin MISMATCH — refusing connection",
                    "Pin key" to pinKey,
                    "Peer" to hostIdentifier,
                    "Stored" to storedPin.take(23),
                    "Presented" to incomingPin.take(23),
                )
                throw CertificateException(
                    "Certificate fingerprint mismatch for $hostIdentifier. " +
                            "If the server certificate was legitimately rotated, " +
                            "an admin must clear the stored pin before reconnecting."
                )
            }
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> =
        systemTrustManager.acceptedIssuers

    // ── Helpers ──────────────────────────────────────────────────────

    private fun computePin(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256")
            .digest(cert.encoded)
            .joinToString(":") { "%02X".format(it) }

    fun clearPin() {
        plainPrefs.edit { remove(pinKey) }
        logger.w("HL7-NSD · Cleared TOFU pin for $pinKey")
    }

    fun currentPin(): String? = getPin()

    private companion object {
        const val LEGACY_PIN_KEY = "pin_pms_server"
    }
}