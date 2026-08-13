package com.rite.pillcounting.core.security

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ModelKeyUnit] covering the deterministic, pure-JVM-reachable logic:
 * fragment assembly, noise-stripping (refine), and hex-to-bytes conversion/validation.
 *
 * The seal/open/persist/retrieve paths and [ModelKeyUnit.activateIfNeeded] /
 * [ModelKeyUnit.material] rely on `KeyStore.getInstance("AndroidKeyStore")`, which is
 * only available on a real device/emulator (Robolectric does not provide a functional
 * AndroidKeyStore security provider) — consistent with the existing precedent in
 * KeystoreAesGcmInstrumentedTest for the same constraint. Those paths are exercised on-device /
 * instrumented instead. Here we use reflection to invoke the private pure helper
 * methods directly, since they contain the only branching/validation logic that is
 * deterministic and does not touch Android Keystore, SharedPreferences, or Cipher.
 */
class ModelKeyUnitTest {

    private val context: Context = mockk(relaxed = true)
    private val unit = ModelKeyUnit(context)

    private fun invokePrivate(name: String, vararg args: Any?): Any? {
        val method = ModelKeyUnit::class.java.getDeclaredMethod(
            name,
            *args.map { it!!::class.java }.toTypedArray()
        )
        method.isAccessible = true
        return method.invoke(unit, *args)
    }

    // ---- compose() / fragment assembly ----

    @Test
    fun `compose assembles all six fragments into the noisy concatenated string`() {
        val composed = invokePrivate("compose") as String
        val expectedRaw = listOf(
            "5e8*!(1e4@!#694&^%42",
            "3a0#\$%bd3\$#&c48!@#90",
            "a6a^&*eec%*^f23@#\$33",
            "154@!#18e&^%3d9%^&39",
            "e11\$#&259!@#481*!(57",
            "b36%*^0d5@#\$600"
        ).joinToString("")
        assertEquals(expectedRaw, composed)
    }

    // ---- refine() noise stripping ----

    @Test
    fun `refine strips all non-alphanumeric noise characters`() {
        val raw = invokePrivate("compose") as String
        val refined = invokePrivate("refine", raw) as String

        assertEquals("5e81e4694423a0bd3c4890a6aeecf233315418e3d939e1125948157b360d5600", refined)
        assertTrue(refined.all { it.isLetterOrDigit() })
    }

    @Test
    fun `refine on already-clean input returns it unchanged`() {
        val clean = "abc123"
        assertEquals(clean, invokePrivate("refine", clean))
    }

    @Test
    fun `refine on empty string returns empty string`() {
        assertEquals("", invokePrivate("refine", ""))
    }

    @Test
    fun `refine on all-noise input returns empty string`() {
        assertEquals("", invokePrivate("refine", "!@#\$%^&*()"))
    }

    // ---- hexToBytes() conversion & validation ----

    @Test
    fun `hexToBytes converts valid 64-char hex string to 32 bytes`() {
        val hex = "5e81e4694423a0bd3c4890a6aeecf233315418e3d939e1125948157b360d5600"
        val bytes = invokePrivate("hexToBytes", hex) as ByteArray

        assertEquals(32, bytes.size)
        assertEquals(0x5e.toByte(), bytes[0])
        assertEquals(0x81.toByte(), bytes[1])
        assertEquals(0x00.toByte(), bytes[31])
    }

    @Test
    fun `hexToBytes trims surrounding whitespace before validation`() {
        val hex = "  " + "a".repeat(64) + "  "
        val bytes = invokePrivate("hexToBytes", hex) as ByteArray
        assertEquals(32, bytes.size)
        assertEquals(0xaa.toByte(), bytes[0])
    }

    @Test(expected = java.lang.reflect.InvocationTargetException::class)
    fun `hexToBytes throws when input is too short`() {
        invokePrivate("hexToBytes", "abcd")
    }

    @Test(expected = java.lang.reflect.InvocationTargetException::class)
    fun `hexToBytes throws when input is too long`() {
        invokePrivate("hexToBytes", "a".repeat(66))
    }

    @Test(expected = java.lang.reflect.InvocationTargetException::class)
    fun `hexToBytes throws when input is empty`() {
        invokePrivate("hexToBytes", "")
    }

    @Test(expected = java.lang.reflect.InvocationTargetException::class)
    fun `hexToBytes throws when input contains non-hex characters`() {
        invokePrivate("hexToBytes", "zz".repeat(32))
    }

    @Test
    fun `hexToBytes on the real composed and refined key produces exactly 32 bytes`() {
        val raw = invokePrivate("compose") as String
        val refined = invokePrivate("refine", raw) as String
        val bytes = invokePrivate("hexToBytes", refined) as ByteArray

        assertEquals(32, bytes.size)
    }

    // ---- destroy() best-effort wipe ----

    @Test
    fun `destroy does not throw for a normal string value`() {
        // destroy() uses reflection on String internals and swallows all exceptions;
        // this verifies it never propagates regardless of JVM string representation.
        invokePrivate("destroy", "some-sensitive-value")
    }
}
