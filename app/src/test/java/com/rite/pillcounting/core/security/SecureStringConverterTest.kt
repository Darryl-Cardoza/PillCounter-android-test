package com.rite.pillcounting.core.security

import com.rite.pillcounting.core.security.models.SecureString
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Tests for [SecureStringConverter], the Room [androidx.room.TypeConverter] bridge
 * between [SecureString] and its encrypted/decrypted string representation.
 *
 * [CryptoHelper] is mocked via [mockkObject] so these tests verify the converter's own
 * delegation logic (null-handling, wrapping/unwrapping into [SecureString]) without
 * depending on the real AndroidKeyStore-backed encryption implementation.
 */
class SecureStringConverterTest {

    @Before
    fun setUp() {
        mockkObject(CryptoHelper)
    }

    @After
    fun tearDown() {
        unmockkObject(CryptoHelper)
    }

    // ---- fromSecure ----

    @Test
    fun `fromSecure returns null when SecureString is null`() {
        val result = SecureStringConverter.fromSecure(null)

        assertNull(result)
        verify(exactly = 0) { CryptoHelper.encryptField(any()) }
    }

    @Test
    fun `fromSecure returns null when SecureString wraps a null value`() {
        val result = SecureStringConverter.fromSecure(SecureString(null))

        assertNull(result)
        verify(exactly = 0) { CryptoHelper.encryptField(any()) }
    }

    @Test
    fun `fromSecure delegates to CryptoHelper encryptField with the wrapped value`() {
        every { CryptoHelper.encryptField("plainText") } returns "iv:cipher:dek"

        val result = SecureStringConverter.fromSecure(SecureString("plainText"))

        assertEquals("iv:cipher:dek", result)
        verify(exactly = 1) { CryptoHelper.encryptField("plainText") }
    }

    @Test
    fun `fromSecure passes empty string value through to encryptField`() {
        every { CryptoHelper.encryptField("") } returns ""

        val result = SecureStringConverter.fromSecure(SecureString(""))

        assertEquals("", result)
        verify(exactly = 1) { CryptoHelper.encryptField("") }
    }

    @Test
    fun `fromSecure returns null when encryptField itself returns null`() {
        every { CryptoHelper.encryptField("plainText") } returns null

        val result = SecureStringConverter.fromSecure(SecureString("plainText"))

        assertNull(result)
    }

    // ---- toSecure ----

    @Test
    fun `toSecure returns null when input string is null`() {
        val result = SecureStringConverter.toSecure(null)

        assertNull(result)
        verify(exactly = 0) { CryptoHelper.decryptField(any()) }
    }

    @Test
    fun `toSecure delegates to CryptoHelper decryptField and wraps result in SecureString`() {
        every { CryptoHelper.decryptField("iv:cipher:dek") } returns "plainText"

        val result = SecureStringConverter.toSecure("iv:cipher:dek")

        assertEquals(SecureString("plainText"), result)
        verify(exactly = 1) { CryptoHelper.decryptField("iv:cipher:dek") }
    }

    @Test
    fun `toSecure wraps null decryptField result in a SecureString holding null`() {
        every { CryptoHelper.decryptField("malformed") } returns null

        val result = SecureStringConverter.toSecure("malformed")

        assertEquals(SecureString(null), result)
    }

    @Test
    fun `toSecure passes empty string through to decryptField`() {
        every { CryptoHelper.decryptField("") } returns ""

        val result = SecureStringConverter.toSecure("")

        assertEquals(SecureString(""), result)
        verify(exactly = 1) { CryptoHelper.decryptField("") }
    }

    // ---- round trip contract ----

    @Test
    fun `fromSecure then toSecure round trip returns original value using real delegation contract`() {
        every { CryptoHelper.encryptField("secret-data") } returns "encrypted-blob"
        every { CryptoHelper.decryptField("encrypted-blob") } returns "secret-data"

        val encrypted = SecureStringConverter.fromSecure(SecureString("secret-data"))
        val decrypted = SecureStringConverter.toSecure(encrypted)

        assertEquals("encrypted-blob", encrypted)
        assertEquals(SecureString("secret-data"), decrypted)
    }
}
