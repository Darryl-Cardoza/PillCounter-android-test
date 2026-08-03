package com.rite.pillcounting.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The one AES/GCM implementation in the app. Used by [DatabaseKeyProvider] to wrap/unwrap the DB
 * DEK with a Keystore-backed KEK — the single KEK/DEK system protecting the entire database.
 */
internal object KeystoreAesGcm {

    private const val ALGO = "AES/GCM/NoPadding"
    private const val TAG_LEN = 128
    private const val IV_LEN = 12
    private const val PROVIDER = "AndroidKeyStore"

    /** Decrypts bytes produced by Keystore-generated-IV [wrap] using [key]. */
    private fun decrypt(key: SecretKey, wrapped: ByteArray): ByteArray {
        val iv = wrapped.copyOfRange(0, IV_LEN)
        val ciphertext = wrapped.copyOfRange(IV_LEN, wrapped.size)
        val cipher = Cipher.getInstance(ALGO)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LEN, iv))
        return cipher.doFinal(ciphertext)
    }

    fun getOrCreateKeystoreKey(alias: String): SecretKey {
        val keystore = KeyStore.getInstance(PROVIDER).also { it.load(null) }
        keystore.getKey(alias, null)?.let { return it as SecretKey }

        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
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

    /** Imports raw external key material (e.g. a server-issued KEK) under [alias]. Wipes [rawKey] after import. */
    fun importKeystoreKey(alias: String, rawKey: ByteArray) {
        try {
            val keystore = KeyStore.getInstance(PROVIDER).also { it.load(null) }
            val protection = android.security.keystore.KeyProtection.Builder(
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
            keystore.setEntry(alias, KeyStore.SecretKeyEntry(SecretKeySpec(rawKey, "AES")), protection)
        } finally {
            rawKey.fill(0)
        }
    }

    fun deleteKeystoreKey(alias: String) {
        val keystore = KeyStore.getInstance(PROVIDER).also { it.load(null) }
        if (keystore.containsAlias(alias)) keystore.deleteEntry(alias)
    }

    fun keystoreKeyExists(alias: String): Boolean {
        val keystore = KeyStore.getInstance(PROVIDER).also { it.load(null) }
        return keystore.containsAlias(alias)
    }

    /** Wraps [plainBytes] with the Keystore key at [alias], generating that key if absent. */
    fun wrap(alias: String, plainBytes: ByteArray): ByteArray {
        val key = getOrCreateKeystoreKey(alias)
        val cipher = Cipher.getInstance(ALGO)
        // Keystore-backed keys must not receive a caller-provided IV on ENCRYPT_MODE — the
        // Keystore generates it internally.
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plainBytes)
        return iv + ciphertext
    }

    /**
     * Unwraps bytes produced by [wrap] using the Keystore key at [alias].
     * Throws [IllegalStateException] if [alias] does not already exist — unlike [wrap], unwrap
     * must never silently generate a fresh (empty) key, or a missing alias would produce a
     * confusing decrypt failure instead of a clear "key not found" error.
     */
    fun unwrap(alias: String, wrapped: ByteArray): ByteArray {
        check(keystoreKeyExists(alias)) { "Keystore key '$alias' not found; cannot unwrap" }
        return decrypt(getOrCreateKeystoreKey(alias), wrapped)
    }
}
