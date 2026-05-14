package com.rite.pillcounting.core.security

import android.content.Context
import android.util.Base64
import com.rite.pillcounting.core.utils.preference.SecurePreferences
import java.security.SecureRandom

object DatabaseKeyProvider {

    private const val KEY_ENCRYPTED_DB_KEY = "encrypted_db_key"

    fun getOrCreateDatabaseKey(context: Context): ByteArray {
        val prefs = SecurePreferences(context, "pillcounting_db_key_prefs")

        val existing = prefs.getString(KEY_ENCRYPTED_DB_KEY)
        if (existing != null) {
            return Base64.decode(existing, Base64.NO_WRAP)
        }

        // Generate a new 32-byte random key
        val newKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.putString(
            KEY_ENCRYPTED_DB_KEY,
            Base64.encodeToString(newKey, Base64.NO_WRAP)
        )
        return newKey
    }
}