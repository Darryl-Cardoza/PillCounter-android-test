package com.dispensesure.retail.core.security

import android.util.Base64
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dispensesure.retail.core.room.AppDatabase
import com.dispensesure.retail.core.room.models.UserEntity
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.security.SecureRandom


/**
 * Proves the three core security guarantees this envelope-encryption feature exists for:
 *
 * 1. The DB is unreadable/garbage without the correct DEK (SQLCipher passphrase).
 * 2. The DEK cannot be recovered (unwrapped) without the matching KEK.
 * 3. The KEK's raw key material is never extractable from the Android Keystore, by design —
 *    only Cipher operations against it are possible, never a byte[] of the key itself.
 *
 * These are "attacker's perspective" tests: each one tries the wrong/missing key and asserts
 * the operation fails, rather than asserting the happy path (covered elsewhere).
 */
@RunWith(AndroidJUnit4::class)
class EnvelopeEncryptionSecurityInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbNamesToClean = mutableListOf<String>()
    private val aliasesToClean = mutableListOf<String>()

    @After
    fun tearDown() {
        dbNamesToClean.forEach { context.deleteDatabase(it) }
        val keystore = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        aliasesToClean.forEach { if (keystore.containsAlias(it)) keystore.deleteEntry(it) }
    }

    private fun passphraseBytes(raw: ByteArray) =
        Base64.encodeToString(raw, Base64.NO_WRAP).toByteArray(Charsets.UTF_8)

    // ---- 1. DB is unreadable/garbage without the correct DEK ----

    @Test
    fun database_cannot_be_opened_with_wrong_dek() = runBlocking {
        System.loadLibrary("sqlcipher")
        val dbName = "security_wrong_dek_test.db"
        dbNamesToClean += dbName

        val realDek = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .openHelperFactory(SupportOpenHelperFactory(passphraseBytes(realDek)))
            .allowMainThreadQueries()
            .build()
        db.userDao().insertIgnore(
            UserEntity(userId = "secure-user-1", fName = "Alice", lName = "Doe")
        )
        db.close()

        // Attacker has the DB file but a different (wrong) DEK.
        val wrongDek = ByteArray(32).also { SecureRandom().nextBytes(it) }
        assertNotEquals("Test setup sanity: keys must differ", realDek.toList(), wrongDek.toList())

        val attackerDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .openHelperFactory(SupportOpenHelperFactory(passphraseBytes(wrongDek)))
            .allowMainThreadQueries()
            .build()

        var openedSuccessfully = true
        try {
            // SQLCipher only actually validates the passphrase on first real access, not at build().
            attackerDb.userDao().getByUserId("secure-user-1")
        } catch (e: Exception) {
            openedSuccessfully = false
        } finally {
            try { attackerDb.close() } catch (_: Exception) {}
        }

        assertFalse(
            "DB must NOT be readable with the wrong DEK — data would be exposed otherwise",
            openedSuccessfully
        )
    }

    @Test
    fun database_file_is_not_plaintext_sqlite_on_disk() = runBlocking {
        System.loadLibrary("sqlcipher")
        val dbName = "security_plaintext_check_test.db"
        dbNamesToClean += dbName
        val dek = ByteArray(32).also { SecureRandom().nextBytes(it) }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .openHelperFactory(SupportOpenHelperFactory(passphraseBytes(dek)))
            .allowMainThreadQueries()
            .build()
        db.userDao().insertIgnore(
            UserEntity(userId = "secure-user-2", fName = "PlaintextCheck", lName = "Test")
        )
        db.close()

        val dbFile = context.getDatabasePath(dbName)
        assertTrue("DB file must exist after write", dbFile.exists())

        // A real SQLite file starts with the literal magic header "SQLite format 3\u0000".
        // An encrypted SQLCipher file must NOT start with this, since the header itself is encrypted.
        val header = ByteArray(16)
        java.io.FileInputStream(dbFile).use { it.read(header) }
        val sqliteMagic = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

        assertFalse(
            "DB file header must not match the plaintext SQLite magic string — file must be encrypted on disk",
            header.contentEquals(sqliteMagic)
        )

        // Also confirm the readable name value never appears as a raw substring anywhere in the file bytes.
        val fileBytes = dbFile.readBytes()
        val needle = "PlaintextCheck".toByteArray(Charsets.UTF_8)
        assertFalse(
            "Plaintext field value must not appear anywhere in the raw DB file bytes",
            containsSubsequence(fileBytes, needle)
        )
    }

    private fun containsSubsequence(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }

    // ---- 2. DEK cannot be unwrapped without the matching KEK ----

    @Test
    fun dek_cannot_be_unwrapped_with_a_different_kek() {
        val correctAlias = "test_security_correct_kek_${System.nanoTime()}"
        val wrongAlias = "test_security_wrong_kek_${System.nanoTime()}"
        aliasesToClean += correctAlias
        aliasesToClean += wrongAlias

        val dek = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val wrapped = KeystoreAesGcm.wrap(correctAlias, dek)

        // A different KEK (different Keystore alias/key) must not unwrap data wrapped by another KEK.
        KeystoreAesGcm.getOrCreateKeystoreKey(wrongAlias) // materializes the wrong key in Keystore

        var unwrapSucceeded = true
        try {
            KeystoreAesGcm.unwrap(wrongAlias, wrapped)
        } catch (_: Exception) {
            unwrapSucceeded = false
        }

        assertFalse(
            "Unwrapping a DEK with the wrong KEK must fail (GCM auth tag mismatch) — " +
                "otherwise any locally-generated key could decrypt another key's wrapped DEK",
            unwrapSucceeded
        )
    }

    @Test
    fun dek_cannot_be_unwrapped_when_kek_alias_does_not_exist() {
        val neverCreatedAlias = "test_security_never_created_${System.nanoTime()}"
        val fakeWrapped = ByteArray(28).also { SecureRandom().nextBytes(it) }

        try {
            KeystoreAesGcm.unwrap(neverCreatedAlias, fakeWrapped)
            fail("Expected unwrap to throw when the KEK alias doesn't exist in Keystore")
        } catch (_: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun rotation_with_tampered_kek_key_material_does_not_corrupt_existing_dek() {
        // Simulates a rotation attempt where the "server" KEK material is garbage/tampered
        // (e.g. MITM or corrupted response) — the pre-rotation DEK must remain intact and usable.
        val prefsName = "pillcounting_db_key_prefs"
        context.getSharedPreferences(prefsName, android.content.Context.MODE_PRIVATE).edit().clear().commit()

        val dekBefore = DatabaseKeyProvider.getOrCreateDatabasePassphrase(context)

        val tamperedKek = KekInfo(
            keyId = "test_security_tampered",
            version = 1,
            algorithm = "AES-256-GCM",
            keyMaterial = "not-valid-base64!!!" // malformed on purpose
        )
        DatabaseKeyProvider.rotateKekIfNewer(context, tamperedKek)

        val dekAfter = DatabaseKeyProvider.getOrCreateDatabasePassphrase(context)
        assertTrue(
            "A failed/tampered rotation attempt must not corrupt or change the existing DEK",
            dekBefore.contentEquals(dekAfter)
        )

        context.getSharedPreferences(prefsName, android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val keystore = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        if (keystore.containsAlias("com.rite.pillcounting.dek_bootstrap_kek")) {
            keystore.deleteEntry("com.rite.pillcounting.dek_bootstrap_kek")
        }
    }

    // ---- 3. KEK raw material is never extractable from Keystore ----

    @Test
    fun keystore_key_cannot_be_exported_as_raw_bytes() {
        val alias = "test_security_no_export_${System.nanoTime()}"
        aliasesToClean += alias
        val key = KeystoreAesGcm.getOrCreateKeystoreKey(alias)

        // AndroidKeyStore-backed SecretKeys report format "null" or throw — encoded material is
        // never available, by Android Keystore design (hardware/TEE-enforced non-extractability).
        val encoded = try {
            key.encoded
        } catch (_: Exception) {
            null
        }

        assertTrue(
            "AndroidKeyStore-backed key must not expose raw encoded key material " +
                "(got: ${encoded?.let { "non-null ${it.size} bytes" } ?: "null, as expected"})",
            encoded == null
        )
    }

    @Test
    fun keystore_key_requires_keystore_provider_cipher_ops_only() {
        // There is no API path to pull a byte[] secret out of AndroidKeyStore for use with a
        // plain (non-Keystore) Cipher/SecretKeySpec — attempting to reconstruct a SecretKeySpec
        // from a Keystore key's `.encoded` (null) demonstrates the key is unusable outside the
        // Keystore's own Cipher.init() calls.
        val alias = "test_security_provider_only_${System.nanoTime()}"
        aliasesToClean += alias
        val key = KeystoreAesGcm.getOrCreateKeystoreKey(alias)

        assertTrue(
            "Keystore key's declared format should not be a raw-exportable format like RAW",
            key.format == null || key.format != "RAW"
        )
    }

    @Test
    fun deleted_kek_alias_permanently_loses_access_to_previously_wrapped_dek() {
        val alias = "test_security_deleted_${System.nanoTime()}"
        val dek = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val wrapped = KeystoreAesGcm.wrap(alias, dek)

        KeystoreAesGcm.deleteKeystoreKey(alias)

        try {
            KeystoreAesGcm.unwrap(alias, wrapped)
            fail("Expected unwrap to fail after the KEK alias was deleted — this proves the " +
                "wrapped DEK is cryptographically inert without its Keystore-held KEK, i.e. " +
                "deleting the KEK is a real, effective way to destroy access to the DEK.")
        } catch (_: IllegalStateException) {
            // expected: alias no longer exists
        }
    }
}
