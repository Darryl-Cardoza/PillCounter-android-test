package com.rite.pillcounting.core.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.security.SecureRandom

/**
 * Exercises [KeystoreAesGcm] against a real Android Keystore. Runs on-device because
 * Robolectric/plain-JVM have no functional "AndroidKeyStore" security provider.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreAesGcmInstrumentedTest {

    private val aliasesToClean = mutableListOf<String>()

    private fun testAlias(name: String): String {
        val alias = "test_${name}_${System.nanoTime()}"
        aliasesToClean += alias
        return alias
    }

    @After
    fun tearDown() {
        val keystore = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        aliasesToClean.forEach { alias ->
            if (keystore.containsAlias(alias)) keystore.deleteEntry(alias)
        }
        aliasesToClean.clear()
    }

    @Test
    fun wrap_then_unwrap_returns_original_plaintext() {
        val alias = testAlias("roundtrip")
        val plain = ByteArray(32).also { SecureRandom().nextBytes(it) }

        val wrapped = KeystoreAesGcm.wrap(alias, plain)
        val unwrapped = KeystoreAesGcm.unwrap(alias, wrapped)

        assertArrayEquals(plain, unwrapped)
    }

    @Test
    fun wrap_produces_different_ciphertext_each_call_due_to_random_iv() {
        val alias = testAlias("iv_uniqueness")
        val plain = ByteArray(32) { 7 }

        val wrapped1 = KeystoreAesGcm.wrap(alias, plain)
        val wrapped2 = KeystoreAesGcm.wrap(alias, plain)

        assertFalse(wrapped1.contentEquals(wrapped2))
        // but both must still unwrap to the same plaintext
        assertArrayEquals(plain, KeystoreAesGcm.unwrap(alias, wrapped1))
        assertArrayEquals(plain, KeystoreAesGcm.unwrap(alias, wrapped2))
    }

    @Test(expected = IllegalStateException::class)
    fun unwrap_throws_when_alias_does_not_exist() {
        val alias = testAlias("missing")
        KeystoreAesGcm.unwrap(alias, ByteArray(28))
    }

    @Test
    fun unwrap_fails_with_tampered_ciphertext_gcm_auth_tag() {
        val alias = testAlias("tamper")
        val plain = ByteArray(16) { 1 }
        val wrapped = KeystoreAesGcm.wrap(alias, plain)
        val tampered = wrapped.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }

        var threw = false
        try {
            KeystoreAesGcm.unwrap(alias, tampered)
        } catch (_: Exception) {
            threw = true
        }
        assertTrue("Tampered ciphertext must fail GCM auth tag check", threw)
    }

    @Test
    fun importKeystoreKey_makes_alias_usable_for_wrap_and_unwrap() {
        val alias = testAlias("imported")
        val rawKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val rawKeyCopy = rawKey.copyOf()

        KeystoreAesGcm.importKeystoreKey(alias, rawKey)

        // caller's buffer must be wiped after import
        assertArrayEquals(ByteArray(32), rawKey)

        val plain = "hello-dek".toByteArray()
        val wrapped = KeystoreAesGcm.wrap(alias, plain)
        assertArrayEquals(plain, KeystoreAesGcm.unwrap(alias, wrapped))
        assertNotEquals(0, rawKeyCopy.sum()) // sanity: original key wasn't all-zero to begin with
    }

    @Test
    fun deleteKeystoreKey_removes_the_alias() {
        val alias = testAlias("delete_me")
        KeystoreAesGcm.wrap(alias, ByteArray(8)) // generates the key

        assertTrue(KeystoreAesGcm.keystoreKeyExists(alias))
        KeystoreAesGcm.deleteKeystoreKey(alias)
        assertFalse(KeystoreAesGcm.keystoreKeyExists(alias))
    }

    @Test
    fun deleteKeystoreKey_on_missing_alias_is_a_no_op() {
        val alias = testAlias("never_created")
        KeystoreAesGcm.deleteKeystoreKey(alias) // must not throw
        assertFalse(KeystoreAesGcm.keystoreKeyExists(alias))
    }
}
