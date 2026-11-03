package com.dispensesure.retail.core.audit.crypto

import android.content.Context
import com.dispensesure.retail.core.audit.AuditLogConfig
import com.dispensesure.retail.core.security.DatabaseKeyProvider
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Supplies the AES key the audit records are encrypted with. */
fun interface AuditKeySource {
    fun key(): SecretKey
}

/**
 * Derives the audit key from the DEK with HKDF, so the audit file never shares key bytes with SQLCipher.
 * Derived once and cached. Call only off the main thread: it unwraps the DEK through the Keystore.
 */
class DekAuditKeySource(private val context: Context) : AuditKeySource {

    @Volatile
    private var cachedKey: SecretKey? = null

    override fun key(): SecretKey =
        cachedKey ?: synchronized(this) { cachedKey ?: deriveKey().also { cachedKey = it } }

    private fun deriveKey(): SecretKey {
        val dek = DatabaseKeyProvider.getOrCreatePassphraseWithoutReset(context)
        try {
            val keyBytes = Hkdf.deriveSha256(
                inputKeyMaterial = dek,
                salt = null,
                info = AuditLogConfig.HKDF_INFO.toByteArray(Charsets.UTF_8),
                outputLengthBytes = AuditLogConfig.KEY_LENGTH_BYTES
            )
            return SecretKeySpec(keyBytes, "AES")
        } finally {
            dek.fill(0)
        }
    }
}
