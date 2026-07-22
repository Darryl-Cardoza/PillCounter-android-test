package com.rite.pillcounting.core.utils.preference

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecurePreferences(context: Context,
                        prefsName: String = PREF_NAME ) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private val keystore by lazy {
        KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
    }

    private fun getOrCreateKey(): SecretKey {
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

        return KeyStore.getInstance("AndroidKeyStore")
            .also { it.load(null) }
            .getKey(KEYSTORE_ALIAS, null) as SecretKey
    }

    // ── Encryption / Decryption ───────────────────────────────────────────

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
        require(parts.size == 2) { "Invalid encrypted value format" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    // ── Public API ────────────────────────────────────────────────────────

    fun putString(key: String, value: String?) {
        if (value == null) {
            prefs.edit { remove(key) }
        } else {
            prefs.edit { putString(key, encrypt(value)) }
        }
    }

    fun getString(key: String, default: String? = null): String? {
        val stored = prefs.getString(key, null) ?: return default
        return try {
            decrypt(stored)
        } catch (e: Exception) {
            stored
        }
    }

    fun putBoolean(key: String, value: Boolean) {
        prefs.edit { putString(key, encrypt(value.toString())) }
    }

    fun getBoolean(key: String, default: Boolean = false): Boolean {
        val stored = prefs.getString(key, null) ?: return default
        return try {
            decrypt(stored).toBoolean()
        } catch (e: Exception) {
            default
        }
    }

    fun putInt(key: String, value: Int) {
        prefs.edit { putString(key, encrypt(value.toString())) }
    }

    fun getInt(key: String, default: Int = 0): Int {
        val stored = prefs.getString(key, null) ?: return default
        return try {
            decrypt(stored).toInt()
        } catch (e: Exception) {
            default
        }
    }

    fun putLong(key: String, value: Long) {
        prefs.edit { putString(key, encrypt(value.toString())) }
    }

    fun getLong(key: String, default: Long = 0L): Long {
        val stored = prefs.getString(key, null) ?: return default
        return try {
            decrypt(stored).toLong()
        } catch (e: Exception) {
            default
        }
    }

    fun contains(key: String): Boolean = prefs.contains(key)

    fun remove(key: String) {
        prefs.edit { remove(key) }
    }

    fun clear() {
        prefs.edit { clear() }
    }

    companion object {
        private const val PREF_NAME = "pillcounting_secure_prefs"
        private const val KEYSTORE_ALIAS = "com.rite.pillcounting.prefs_key"
    }
}