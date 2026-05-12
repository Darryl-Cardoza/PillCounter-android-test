package com.rite.pillcounting.core.security

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

object DatabaseKeyProvider {

    private const val KEY_ALIAS = "pill_counting_db_key"
    private const val PREFS_NAME = "db_key_prefs"
    private const val KEY_ENCRYPTED_DB_KEY = "encrypted_db_key"

    fun getOrCreateDatabaseKey(context: Context): ByteArray {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        val encryptedPrefs = EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        val existing = encryptedPrefs.getString(KEY_ENCRYPTED_DB_KEY, null)
        if (existing != null) {
            return Base64.decode(existing, Base64.NO_WRAP)
        }

        // Generate a new 32-byte random key
        val newKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        encryptedPrefs.edit {
            putString(KEY_ENCRYPTED_DB_KEY, Base64.encodeToString(newKey, Base64.NO_WRAP))
        }
        return newKey
    }
}