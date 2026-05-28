package com.rite.pillcounting.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM image encryption backed by the Android Keystore.
 *
 * File format on disk:
 *   [ MAGIC (4 bytes "RENC") ][ IV (12 bytes) ][ AES-GCM ciphertext + 16-byte auth tag ]
 *
 * The MAGIC prefix lets [isEncrypted] distinguish new encrypted files from
 * legacy plain JPEG files so both can be decoded transparently during migration.
 */
object ImageCrypto {

    private const val KEY_ALIAS = "pill_image_enc_key"
    private const val ALGO = "AES/GCM/NoPadding"
    private const val TAG_LEN = 128
    private const val IV_LEN = 12
    private val MAGIC = "RENC".toByteArray(Charsets.US_ASCII)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        keyGen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return keyGen.generateKey()
    }

    /** Encrypts raw image bytes and prepends MAGIC + IV. */
    fun encrypt(bytes: ByteArray): ByteArray {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(ALGO)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(bytes)
        return MAGIC + iv + encrypted
    }

    /** Decrypts bytes previously produced by [encrypt]. */
    fun decrypt(bytes: ByteArray): ByteArray {
        require(bytes.size > MAGIC.size + IV_LEN) { "Invalid encrypted image data" }
        val iv = bytes.copyOfRange(MAGIC.size, MAGIC.size + IV_LEN)
        val ciphertext = bytes.copyOfRange(MAGIC.size + IV_LEN, bytes.size)
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(ALGO)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LEN, iv))
        return cipher.doFinal(ciphertext)
    }

    /** Returns true if [bytes] starts with the RENC magic header. */
    fun isEncrypted(bytes: ByteArray): Boolean =
        bytes.size > MAGIC.size && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)
}
