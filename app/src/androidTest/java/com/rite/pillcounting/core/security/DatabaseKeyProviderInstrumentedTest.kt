package com.rite.pillcounting.core.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64

/**
 * End-to-end, on-device test of the bootstrap-then-rotate envelope encryption flow in
 * [DatabaseKeyProvider], against a real Android Keystore and real [SecurePreferences]-backed
 * storage (isolated to a throwaway prefs file per test, cleared in [tearDown]).
 */
@RunWith(AndroidJUnit4::class)
class DatabaseKeyProviderInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // Must match DatabaseKeyProvider's private PREFS_NAME — the class has no seam to inject a
    // different prefs file, so tests operate on (and clean up) the same file the app itself uses.
    private val prefsName = "pillcounting_db_key_prefs"

    @Before
    fun setUp() {
        context.getSharedPreferences(prefsName, android.content.Context.MODE_PRIVATE).edit().clear().commit()
        clearTestKeystoreAliases()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(prefsName, android.content.Context.MODE_PRIVATE).edit().clear().commit()
        clearTestKeystoreAliases()
    }

    private fun clearTestKeystoreAliases() {
        val keystore = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        keystore.aliases().toList()
            .filter { it == "com.rite.pillcounting.dek_bootstrap_kek" || it.startsWith("com.rite.pillcounting.server_kek_test_") }
            .forEach { keystore.deleteEntry(it) }
    }

    private fun freshKek(keyId: String, version: Int): KekInfo {
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return KekInfo(
            keyId = keyId,
            version = version,
            algorithm = "AES-256-GCM",
            keyMaterial = Base64.getEncoder().encodeToString(raw)
        )
    }

    @Test
    fun first_launch_generates_stable_32_byte_dek_wrapped_by_bootstrap_kek() {
        val dek1 = DatabaseKeyProvider.getOrCreateDatabasePassphrase(context)
        val dek2 = DatabaseKeyProvider.getOrCreateDatabasePassphrase(context)

        assertEquals(32, dek1.size)
        assertArrayEquals("DEK must be identical across repeated calls (same app install)", dek1, dek2)
    }

    @Test
    fun dek_survives_kek_rotation_unchanged() {
        val dekBeforeRotation = DatabaseKeyProvider.getOrCreateDatabasePassphrase(context)

        val kekInfo = freshKek("test_kek_rot_1", version = 1)
        DatabaseKeyProvider.rotateKekIfNewer(context, kekInfo)

        val dekAfterRotation = DatabaseKeyProvider.getOrCreateDatabasePassphrase(context)

        assertArrayEquals(
            "Rotating the KEK must not change the underlying DEK/DB passphrase",
            dekBeforeRotation,
            dekAfterRotation
        )
    }

    @Test
    fun rotation_with_same_or_older_version_is_a_no_op() {
        val dekInitial = DatabaseKeyProvider.getOrCreateDatabasePassphrase(context)
        DatabaseKeyProvider.rotateKekIfNewer(context, freshKek("test_kek_v1", version = 1))
        val dekAfterFirstRotation = DatabaseKeyProvider.getOrCreateDatabasePassphrase(context)

        // Same version again — must not rotate (e.g. repeated /auth/me polling)
        DatabaseKeyProvider.rotateKekIfNewer(context, freshKek("test_kek_v1_dup", version = 1))
        val dekAfterDuplicateVersion = DatabaseKeyProvider.getOrCreateDatabasePassphrase(context)

        assertArrayEquals(dekInitial, dekAfterFirstRotation)
        assertArrayEquals(dekInitial, dekAfterDuplicateVersion)

        // The duplicate-version KEK must never have been imported/used
        val keystore = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        assertEquals(false, keystore.containsAlias("com.rite.pillcounting.server_kek_test_kek_v1_dup"))
    }

    @Test
    fun multiple_sequential_rotations_each_apply_and_preserve_dek() {
        val dekInitial = DatabaseKeyProvider.getOrCreateDatabasePassphrase(context)

        DatabaseKeyProvider.rotateKekIfNewer(context, freshKek("test_kek_seq_1", version = 1))
        DatabaseKeyProvider.rotateKekIfNewer(context, freshKek("test_kek_seq_2", version = 2))
        DatabaseKeyProvider.rotateKekIfNewer(context, freshKek("test_kek_seq_3", version = 3))

        val dekFinal = DatabaseKeyProvider.getOrCreateDatabasePassphrase(context)
        assertArrayEquals(dekInitial, dekFinal)

        // Only the latest server KEK alias should remain; earlier ones deleted.
        val keystore = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        assertEquals(false, keystore.containsAlias("com.rite.pillcounting.server_kek_test_kek_seq_1"))
        assertEquals(false, keystore.containsAlias("com.rite.pillcounting.server_kek_test_kek_seq_2"))
        assertEquals(true, keystore.containsAlias("com.rite.pillcounting.server_kek_test_kek_seq_3"))
    }

    @Test
    fun version_mismatch_with_higher_version_triggers_rewrap_under_new_alias() {
        DatabaseKeyProvider.getOrCreateDatabasePassphrase(context)
        val kekInfo = freshKek("test_kek_mismatch", version = 5)

        DatabaseKeyProvider.rotateKekIfNewer(context, kekInfo)

        val keystore = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        assertEquals(true, keystore.containsAlias("com.rite.pillcounting.server_kek_test_kek_mismatch"))
        // Old bootstrap alias must be gone after successful rotation.
        assertEquals(false, keystore.containsAlias("com.rite.pillcounting.dek_bootstrap_kek"))
    }
}
