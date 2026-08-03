package com.rite.pillcounting.core.security

import android.content.Context
import android.util.Base64
import com.rite.pillcounting.core.utils.preference.SecurePreferences
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.feature.dashboard.domain.model.KekInfo
import java.security.SecureRandom

/**
 * Provides the SQLCipher DB passphrase (DEK) using envelope encryption.
 *
 * The DEK is generated once locally and never changes. At rest it is stored only in its
 * wrapped (encrypted) form, wrapped by a KEK held in the Android Keystore:
 * - On first-ever launch, before any server KEK is available, the DEK is wrapped by a
 *   local bootstrap KEK ([BOOTSTRAP_KEK_ALIAS]) generated on-device.
 * - Once the server issues a KEK (see [KekInfo], delivered via `/auth/me`), [rotateKekIfNewer]
 *   unwraps the DEK with the old KEK and re-wraps it with the new one. The DEK itself, and
 *   therefore the DB passphrase, never changes across a rotation.
 */
object DatabaseKeyProvider {

    private const val PREFS_NAME = "pillcounting_db_key_prefs"
    private const val KEY_DEK_WRAPPED = "dek_wrapped"
    private const val KEY_KEK_ID = "dek_kek_id"
    private const val KEY_KEK_VERSION = "dek_kek_version"

    private const val BOOTSTRAP_KEK_ID = "local-bootstrap"
    private const val BOOTSTRAP_KEK_ALIAS = "com.rite.pillcounting.dek_bootstrap_kek"
    private const val SERVER_KEK_ALIAS_PREFIX = "com.rite.pillcounting.server_kek_"

    private val logger = AppLogger.create<DatabaseKeyProvider>()

    // Guards all read-check-unwrap-wrap-write-delete sequences below against concurrent
    // callers (e.g. /auth/me polled repeatedly can call rotateKekIfNewer from overlapping
    // coroutines) — without it, two racing calls can corrupt the wrapped DEK or delete a
    // Keystore alias the other call is still mid-unwrap on.
    private val lock = Any()

    private fun serverKekAlias(keyId: String) = "$SERVER_KEK_ALIAS_PREFIX$keyId"

    private fun aliasFor(kekId: String) =
        if (kekId == BOOTSTRAP_KEK_ID) BOOTSTRAP_KEK_ALIAS else serverKekAlias(kekId)

    /** Returns the raw DEK bytes to use as the SQLCipher passphrase, generating/wrapping it on first use. */
    fun getOrCreateDatabasePassphrase(context: Context): ByteArray = synchronized(lock) {
        val prefs = SecurePreferences(context, PREFS_NAME)

        val wrappedB64 = prefs.getString(KEY_DEK_WRAPPED)
        val kekId = prefs.getString(KEY_KEK_ID)

        if (wrappedB64 != null && kekId != null) {
            return try {
                KeystoreAesGcm.unwrap(aliasFor(kekId), Base64.decode(wrappedB64, Base64.NO_WRAP))
            } catch (e: Exception) {
                // Keystore key missing/invalidated (e.g. partial data clear) — local txn data is
                // disposable/synced to PMS, so recover by re-keying rather than failing to open the DB.
                logger.e("Failed to unwrap DEK, re-keying database", e)
                prefs.clear()
                generateAndBootstrapWrapDek(prefs)
            }
        }

        return generateAndBootstrapWrapDek(prefs)
    }

    private fun generateAndBootstrapWrapDek(prefs: SecurePreferences): ByteArray {
        val dek = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val wrapped = KeystoreAesGcm.wrap(BOOTSTRAP_KEK_ALIAS, dek)
        prefs.putString(KEY_DEK_WRAPPED, Base64.encodeToString(wrapped, Base64.NO_WRAP))
        prefs.putString(KEY_KEK_ID, BOOTSTRAP_KEK_ID)
        prefs.putInt(KEY_KEK_VERSION, 0)
        return dek
    }

    /**
     * Re-wraps the existing DEK under a newly issued server KEK, if [kekInfo]'s version is newer
     * than what's stored locally. No-op otherwise (`/auth/me` may be polled repeatedly with an
     * unchanged KEK). The DB passphrase itself never changes — only its wrapper.
     */
    fun rotateKekIfNewer(context: Context, kekInfo: KekInfo): Unit = synchronized(lock) {
        val prefs = SecurePreferences(context, PREFS_NAME)
        val currentVersion = prefs.getInt(KEY_KEK_VERSION, -1)

        if (kekInfo.version <= currentVersion) return

        val oldKekId = prefs.getString(KEY_KEK_ID)
        val oldWrappedB64 = prefs.getString(KEY_DEK_WRAPPED)
        if (oldKekId == null || oldWrappedB64 == null) {
            logger.e("Cannot rotate KEK: no existing wrapped DEK found")
            return
        }

        val newAlias = serverKekAlias(kekInfo.keyId)
        try {
            val rawKek = Base64.decode(kekInfo.keyMaterial, Base64.NO_WRAP)
            KeystoreAesGcm.importKeystoreKey(newAlias, rawKek)

            val dek = KeystoreAesGcm.unwrap(aliasFor(oldKekId), Base64.decode(oldWrappedB64, Base64.NO_WRAP))
            val newWrapped = KeystoreAesGcm.wrap(newAlias, dek)

            prefs.putString(KEY_DEK_WRAPPED, Base64.encodeToString(newWrapped, Base64.NO_WRAP))
            prefs.putString(KEY_KEK_ID, kekInfo.keyId)
            prefs.putInt(KEY_KEK_VERSION, kekInfo.version)
            logger.d("Rotated DB KEK to ${kekInfo.keyId} (version ${kekInfo.version})")
        } catch (e: Exception) {
            logger.e("KEK rotation failed, keeping previous key", e)
            // Roll back the newly imported Keystore alias so a failed rotation doesn't leave an
            // orphaned key behind — the old alias (and its wrapped DEK in prefs) is still intact,
            // since prefs are only committed above after every prior step succeeds.
            KeystoreAesGcm.deleteKeystoreKey(newAlias)
            return
        }

        // Only delete the old alias once prefs durably point at the new one — if this throws,
        // prefs are already correct and the stale alias is merely leaked, not fatal.
        try {
            KeystoreAesGcm.deleteKeystoreKey(aliasFor(oldKekId))
        } catch (e: Exception) {
            logger.e("Failed to delete old KEK alias after successful rotation", e)
        }
    }
}
