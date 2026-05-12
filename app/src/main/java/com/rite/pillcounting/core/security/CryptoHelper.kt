package com.rite.pillcounting.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.rite.pillcounting.core.security.CryptoHelper.encryptField
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Provides AES-GCM-based field-level encryption and decryption using a dual-key
 * (KEK/DEK) architecture.
 *
 * - **KEK (Key Encryption Key):** Derived deterministically from a root secret or Keystore.
 * - **DEK (Data Encryption Key):** Randomly generated per record and wrapped by the KEK.
 * - **AES/GCM/NoPadding:** Ensures authenticated encryption with integrity.
 *
 * Each encrypted payload includes the IV, ciphertext, and wrapped DEK, concatenated with `:`.
 */
object CryptoHelper {

    private const val ALGO = "AES/GCM/NoPadding"
    private const val TAG_LEN = 128
    private const val KEYSTORE_ALIAS = "com.rite.pillcounting.crypto_helper_kek"
    private val random = SecureRandom()

    // MARK: - KEK via Android Keystore
    // Replaces deriveKekKey("pillcounting-root-secret")
    // Key is generated once, stored in hardware-backed Keystore, never leaves it

    private fun getOrCreateKek(): SecretKey {
        val keystore = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }

        keystore.getKey(KEYSTORE_ALIAS, null)?.let { return it as SecretKey }

        KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        ).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
        }.generateKey()

        return keystore.getKey(KEYSTORE_ALIAS, null) as SecretKey
    }

    // MARK: - Public API (unchanged from before)

    fun encryptField(plain: String?): String? {
        if (plain.isNullOrBlank()) return plain

        val dek = generateDek()
        val wrappedDek = wrapDek(dek.encoded)
        val iv = ByteArray(12).apply { random.nextBytes(this) }

        val cipher = Cipher.getInstance(ALGO)
        cipher.init(Cipher.ENCRYPT_MODE, dek, GCMParameterSpec(TAG_LEN, iv))
        val ciphertext = cipher.doFinal(plain.toByteArray())

        return buildString {
            append(Base64.encodeToString(iv, Base64.NO_WRAP))
            append(":")
            append(Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            append(":")
            append(Base64.encodeToString(wrappedDek, Base64.NO_WRAP))
        }
    }

    fun decryptField(enc: String?): String? {
        if (enc.isNullOrBlank()) return enc
        val parts = enc.split(":")
        if (parts.size != 3) return enc

        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
        val wrappedDek = Base64.decode(parts[2], Base64.NO_WRAP)

        val dek = SecretKeySpec(unwrapDek(wrappedDek), "AES")
        val cipher = Cipher.getInstance(ALGO)
        cipher.init(Cipher.DECRYPT_MODE, dek, GCMParameterSpec(TAG_LEN, iv))
        return String(cipher.doFinal(ciphertext))
    }

    // MARK: - DEK Wrap / Unwrap
    // Now uses Keystore-backed KEK instead of the hardcoded derived key

    private fun wrapDek(dekBytes: ByteArray): ByteArray {
        val kek = getOrCreateKek()
        val iv = ByteArray(12).apply { random.nextBytes(this) }
        val cipher = Cipher.getInstance(ALGO)
        cipher.init(Cipher.ENCRYPT_MODE, kek, GCMParameterSpec(TAG_LEN, iv))
        return iv + cipher.doFinal(dekBytes)
    }

    private fun unwrapDek(wrapped: ByteArray): ByteArray {
        val kek = getOrCreateKek()
        val iv = wrapped.copyOfRange(0, 12)
        val enc = wrapped.copyOfRange(12, wrapped.size)
        val cipher = Cipher.getInstance(ALGO)
        cipher.init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(TAG_LEN, iv))
        return cipher.doFinal(enc)
    }

    // MARK: - DEK Generation (unchanged)

    private fun generateDek(): SecretKey {
        return KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }
}
