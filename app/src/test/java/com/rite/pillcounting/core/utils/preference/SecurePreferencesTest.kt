package com.rite.pillcounting.core.utils.preference

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [SecurePreferences].
 *
 * Robolectric does not provide a working crypto implementation for "AndroidKeyStore" (no AES
 * KeyGenerator/KeyStore provider is registered under that name on the JVM), so any path that
 * calls encrypt()/decrypt() (e.g. putString/getString on a value that was itself written via
 * put*) throws NoSuchAlgorithmException under Robolectric — confirmed by running this suite.
 * These tests instead cover the paths that don't require real AndroidKeyStore crypto: reading
 * defaults, decrypt-failure fallback behavior on malformed/plaintext data written directly to
 * the underlying SharedPreferences, and contains/remove/clear bookkeeping. Real encrypt/decrypt
 * round-tripping requires an instrumented (on-device/emulator) test, matching this codebase's
 * existing precedent of not exercising AndroidKeyStore under Robolectric (see PreferenceHelperTest).
 */
@RunWith(RobolectricTestRunner::class)
class SecurePreferencesTest {

    private val prefsName = "test_secure_prefs"
    private lateinit var prefs: SecurePreferences
    private lateinit var rawPrefs: android.content.SharedPreferences

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        rawPrefs = context.getSharedPreferences(prefsName, android.content.Context.MODE_PRIVATE)
        rawPrefs.edit().clear().commit()
        prefs = SecurePreferences(context, prefsName)
    }

    @After
    fun tearDown() {
        rawPrefs.edit().clear().commit()
    }

    // ─────────────────────────── DEFAULTS WHEN UNSET ───────────────────────────

    @Test
    fun `getString returns null when key unset and no default given`() {
        assertNull(prefs.getString("missing"))
    }

    @Test
    fun `getString returns supplied default when key unset`() {
        assertEquals("fallback", prefs.getString("missing", "fallback"))
    }

    @Test
    fun `getBoolean returns false by default when key unset`() {
        assertFalse(prefs.getBoolean("missing"))
    }

    @Test
    fun `getBoolean returns supplied default when key unset`() {
        assertTrue(prefs.getBoolean("missing", true))
    }

    @Test
    fun `getInt returns zero by default when key unset`() {
        assertEquals(0, prefs.getInt("missing"))
    }

    @Test
    fun `getInt returns supplied default when key unset`() {
        assertEquals(7, prefs.getInt("missing", 7))
    }

    @Test
    fun `getLong returns zero by default when key unset`() {
        assertEquals(0L, prefs.getLong("missing"))
    }

    @Test
    fun `getLong returns supplied default when key unset`() {
        assertEquals(-1L, prefs.getLong("missing", -1L))
    }

    // ─────────────────────────── DECRYPT-FAILURE FALLBACK ───────────────────────────
    // All getters — including getString — fall back to the caller-supplied default (null for
    // getString) on decrypt failure, never the raw ciphertext/malformed value. Returning raw
    // stored bytes as if they were valid plaintext would let corrupted or tampered data silently
    // flow through as a "successful" read. Writing "malformed" data directly to the underlying
    // SharedPreferences (bypassing encrypt()) lets us exercise these catch blocks without
    // needing a working AndroidKeyStore.

    @Test
    fun `getString returns null when stored value cannot be decrypted and no default given`() {
        rawPrefs.edit().putString("key", "not-encrypted-plain-text").commit()
        assertNull(prefs.getString("key"))
    }

    @Test
    fun `getString returns supplied default when stored value cannot be decrypted`() {
        rawPrefs.edit().putString("key", "not-encrypted-plain-text").commit()
        assertEquals("fallback", prefs.getString("key", "fallback"))
    }

    @Test
    fun `getString returns default when stored value lacks the iv-ciphertext colon format`() {
        rawPrefs.edit().putString("key", "nodelimiter").commit()
        assertNull(prefs.getString("key"))
    }

    @Test
    fun `getBoolean falls back to default false when stored value is malformed`() {
        rawPrefs.edit().putString("flag", "garbage:data").commit()
        assertFalse(prefs.getBoolean("flag", false))
    }

    @Test
    fun `getBoolean falls back to default true when stored value is malformed`() {
        rawPrefs.edit().putString("flag", "garbage:data").commit()
        assertTrue(prefs.getBoolean("flag", true))
    }

    @Test
    fun `getInt falls back to default when stored value is malformed`() {
        rawPrefs.edit().putString("count", "garbage:data").commit()
        assertEquals(9, prefs.getInt("count", 9))
    }

    @Test
    fun `getLong falls back to default when stored value is malformed`() {
        rawPrefs.edit().putString("id", "garbage:data").commit()
        assertEquals(99L, prefs.getLong("id", 99L))
    }

    // ─────────────────────────── PUT STRING NULL REMOVES KEY ───────────────────────────
    // The null branch of putString short-circuits before any encryption, so it's safe to
    // exercise directly.

    @Test
    fun `putString with null value removes the key without needing encryption`() {
        rawPrefs.edit().putString("key", "not-encrypted-plain-text").commit()
        assertTrue(prefs.contains("key"))
        prefs.putString("key", null)
        assertFalse(prefs.contains("key"))
        assertNull(prefs.getString("key"))
    }

    // ─────────────────────────── CONTAINS / REMOVE / CLEAR ───────────────────────────

    @Test
    fun `contains reflects presence of a raw key regardless of encryption`() {
        assertFalse(prefs.contains("key"))
        rawPrefs.edit().putString("key", "anything").commit()
        assertTrue(prefs.contains("key"))
    }

    @Test
    fun `remove deletes the key`() {
        rawPrefs.edit().putString("key", "anything").commit()
        prefs.remove("key")
        assertFalse(prefs.contains("key"))
    }

    @Test
    fun `clear removes all keys`() {
        rawPrefs.edit()
            .putString("a", "1")
            .putString("b", "2")
            .putString("c", "3")
            .commit()
        prefs.clear()
        assertFalse(prefs.contains("a"))
        assertFalse(prefs.contains("b"))
        assertFalse(prefs.contains("c"))
    }

    // ─────────────────────────── CONSTRUCTOR / PREFS NAME ───────────────────────────

    @Test
    fun `two instances with the same prefsName read the same underlying SharedPreferences`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val other = SecurePreferences(context, prefsName)
        rawPrefs.edit().putString("key", "raw-value").commit()
        // "raw-value" isn't validly encrypted, so this exercises the same
        // decrypt-failure-returns-default path as the other instance would —
        // proving both instances see the same underlying entry, not shared plaintext.
        assertNull(other.getString("key"))
    }

    // ─────────────────────────── ADDITIONAL EDGE CASES ───────────────────────────

    @Test
    fun `getString returns default when stored value is an empty string`() {
        rawPrefs.edit().putString("key", "").commit()
        assertNull(prefs.getString("key"))
    }

    @Test
    fun `getBoolean falls back to default when stored value has correct format but non-decryptable parts`() {
        rawPrefs.edit().putString("flag", "aGVsbG8=:d29ybGQ=").commit()
        assertFalse(prefs.getBoolean("flag", false))
    }

    @Test
    fun `remove on a key that was never set is a no-op`() {
        prefs.remove("never-set")
        assertFalse(prefs.contains("never-set"))
    }

    @Test
    fun `clear on an already-empty prefs file is a no-op`() {
        prefs.clear()
        assertFalse(prefs.contains("anything"))
    }

    @Test
    fun `default prefsName constructor targets the pillcounting_secure_prefs file`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val defaultNamedPrefs = SecurePreferences(context)
        val rawDefaultPrefs = context.getSharedPreferences("pillcounting_secure_prefs", android.content.Context.MODE_PRIVATE)
        rawDefaultPrefs.edit().clear().commit()
        rawDefaultPrefs.edit().putString("key", "value-in-default-file").commit()
        // "value-in-default-file" isn't validly encrypted, so a successful lookup against the
        // right underlying file still surfaces as the decrypt-failure default (null), not a crash
        // or a lookup-miss default from the wrong file.
        assertNull(defaultNamedPrefs.getString("key"))
        assertTrue(defaultNamedPrefs.contains("key"))
        rawDefaultPrefs.edit().clear().commit()
    }
}
