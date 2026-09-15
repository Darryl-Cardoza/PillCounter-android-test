package com.rite.pillcounting.core.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Securely assembles and vends the AES-256 model decryption key.
 *
 * Mirrors the RuntimeUnit pattern:
 *   - Key hex is split across obfuscated fragments (noise stripped at runtime)
 *   - Assembled value is sealed with a Keystore-backed AES-GCM key
 *   - Sealed payload is stored in plain SharedPreferences (payload is encrypted)
 *   - [material] returns the raw key bytes ready for use in AES/GCM decryption
 *
 * Usage:
 *   val unit = ModelKeyUnit(context)
 *   unit.activateIfNeeded()          // call once on app start (idempotent)
 *   val keyBytes = unit.material()   // call when decrypting a model file
 */
class ModelKeyUnit(private val context: Context) {

    // MARK: - PUBLIC INTERFACE

    fun activateIfNeeded() {
        if (existsInStore()) return
        val raw = compose()
        val refined = refine(raw)
        persist(seal(refined))
        destroy(refined)
    }

    /**
     * Returns the 32-byte AES-256 model decryption key.
     * Call this immediately before decrypting a model file; do not cache the result.
     */
    @Throws(Exception::class)
    fun material(): ByteArray {
        val sealed = retrieve()
        val hex = open(sealed)
        return hexToBytes(hex)
    }

    // MARK: - ASSEMBLY

    private fun compose(): String = listOf(f1(), f2(), f3(), f4(), f5(), f6()).joinToString("")

    private fun refine(value: String): String = value.filter { it.isLetterOrDigit() }

    private fun destroy(value: String) {
        try {
            val field = String::class.java.getDeclaredField("value")
            field.isAccessible = true
            val chars = field.get(value)
            when (chars) {
                is ByteArray -> chars.fill(0)
                is CharArray -> chars.fill('\u0000')
            }
        } catch (_: Exception) { }
    }

    // MARK: - FRAGMENTS
    // Each fragment embeds hex chars with interspersed noise; refine() strips non-alphanumeric.

    private fun f1() = "5e8*!(1e4@!#694&^%42"
    private fun f2() = "3a0#\$%bd3\$#&c48!@#90"
    private fun f3() = "a6a^&*eec%*^f23@#\$33"
    private fun f4() = "154@!#18e&^%3d9%^&39"
    private fun f5() = "e11\$#&259!@#481*!(57"
    private fun f6() = "b36%*^0d5@#\$600"

    // MARK: - ANDROID KEYSTORE
    // Key 1: encrypts the assembled hex string in memory (seal/open)
    // Key 2: encrypts the stored payload in SharedPreferences (persist/retrieve)

    private val runtimeKeyAlias = "com.model.key.unit.node"
    private val storageKeyAlias = "com.model.key.unit.storage"

    private fun getOrCreateKey(alias: String): SecretKey {
        val keystore = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        keystore.getKey(alias, null)?.let { return it as SecretKey }

        KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        ).apply {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
        }.generateKey()

        return KeyStore.getInstance("AndroidKeyStore")
            .also { it.load(null) }
            .getKey(alias, null) as SecretKey
    }

    // MARK: - SEAL / OPEN

    private fun seal(value: String): ByteArray {
        val key = getOrCreateKey(runtimeKeyAlias)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return ByteBuffer.allocate(4 + iv.size + ciphertext.size)
            .putInt(iv.size)
            .put(iv)
            .put(ciphertext)
            .array()
    }

    private fun open(data: ByteArray): String {
        val key = getOrCreateKey(runtimeKeyAlias)
        val buffer = ByteBuffer.wrap(data)
        val ivLen = buffer.int
        val iv = ByteArray(ivLen).also { buffer.get(it) }
        val ciphertext = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    // MARK: - STORAGE

    private val storageID = "model_key_unit_payload_v1"
    private val prefsName = "model_key_unit_store"

    private val plainPrefs: SharedPreferences by lazy {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    }

    private fun existsInStore(): Boolean = plainPrefs.contains(storageID)

    private fun persist(data: ByteArray) {
        val encrypted = encryptForStorage(data)
        plainPrefs.edit { putString(storageID, encrypted) }
    }

    private fun retrieve(): ByteArray {
        val encrypted = plainPrefs.getString(storageID, null)
            ?: throw IllegalStateException("ModelKeyUnit: payload not found in store")
        return decryptFromStorage(encrypted)
    }

    private fun encryptForStorage(data: ByteArray): String {
        val key = getOrCreateKey(storageKeyAlias)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(data)
        return "${Base64.encodeToString(iv, Base64.NO_WRAP)}:${
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        }"
    }

    private fun decryptFromStorage(stored: String): ByteArray {
        val parts = stored.split(":")
        require(parts.size == 2) { "ModelKeyUnit: invalid stored format" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
        val key = getOrCreateKey(storageKeyAlias)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    // MARK: - UTILITIES

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        require(clean.length == 64) { "ModelKeyUnit: expected 64 hex chars (32 bytes), got ${clean.length}" }
        return ByteArray(32) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
