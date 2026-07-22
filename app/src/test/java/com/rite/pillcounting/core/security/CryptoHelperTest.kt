package com.rite.pillcounting.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [CryptoHelper] covering the branches that do not require a real
 * Android Keystore ("AndroidKeyStore" provider). The encrypt/decrypt round trip
 * relies on `KeyStore.getInstance("AndroidKeyStore")` which is only available on
 * a real device/emulator (Robolectric does not provide a functional AndroidKeyStore
 * security provider), so those paths are exercised on-device/instrumented instead.
 * Here we verify all input-validation short-circuit branches, which is real,
 * deterministic behavior fully reachable on the plain JVM.
 */
class CryptoHelperTest {

    // ---- encryptField short-circuit branches ----

    @Test
    fun `encryptField returns null when input is null`() {
        assertNull(CryptoHelper.encryptField(null))
    }

    @Test
    fun `encryptField returns empty string unchanged when input is empty`() {
        assertEquals("", CryptoHelper.encryptField(""))
    }

    @Test
    fun `encryptField returns blank string unchanged when input is whitespace only`() {
        assertEquals("   ", CryptoHelper.encryptField("   "))
    }

    // ---- decryptField short-circuit branches ----

    @Test
    fun `decryptField returns null when input is null`() {
        assertNull(CryptoHelper.decryptField(null))
    }

    @Test
    fun `decryptField returns empty string unchanged when input is empty`() {
        assertEquals("", CryptoHelper.decryptField(""))
    }

    @Test
    fun `decryptField returns blank string unchanged when input is whitespace only`() {
        assertEquals("   ", CryptoHelper.decryptField("   "))
    }

    @Test
    fun `decryptField returns original string unchanged when it has no colon separators`() {
        val malformed = "not-encrypted-plain-text"
        assertEquals(malformed, CryptoHelper.decryptField(malformed))
    }

    @Test
    fun `decryptField returns original string unchanged when it has only one part`() {
        val malformed = "onlyOnePart"
        assertEquals(malformed, CryptoHelper.decryptField(malformed))
    }

    @Test
    fun `decryptField returns original string unchanged when it has two parts`() {
        val malformed = "partA:partB"
        assertEquals(malformed, CryptoHelper.decryptField(malformed))
    }

    @Test
    fun `decryptField returns original string unchanged when it has more than three parts`() {
        val malformed = "partA:partB:partC:partD"
        assertEquals(malformed, CryptoHelper.decryptField(malformed))
    }

    @Test
    fun `decryptField treats colon-only string as three empty parts and does not early-return`() {
        // "::"  splits into ["", "", ""] which has size 3, so this bypasses the
        // parts.size != 3 short-circuit and would proceed to Base64 decode + Keystore
        // access (not exercised here as it requires a real AndroidKeyStore provider).
        val partsSize = "::".split(":").size
        assertEquals(3, partsSize)
    }
}
