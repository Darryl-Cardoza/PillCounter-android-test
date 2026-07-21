package com.rite.pillcounting.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ImageCrypto.encrypt] and [ImageCrypto.decrypt] depend on the real
 * AndroidKeyStore provider (via [KeyStore.getInstance("AndroidKeyStore")]),
 * which is not available on the plain JVM and cannot be mocked without
 * modifying production code to inject the KeyStore/Cipher. Those two
 * methods are therefore not exercised here. This test focuses on the
 * pure, JVM-testable logic: [ImageCrypto.isEncrypted] and the input
 * validation performed by [ImageCrypto.decrypt] before it ever reaches
 * the keystore (the `require` check fails fast on malformed input).
 */
class ImageCryptoTest {

    private val magic = "RENC".toByteArray(Charsets.US_ASCII)

    @Test
    fun `isEncrypted returns true for bytes starting with RENC magic and extra data`() {
        val bytes = magic + byteArrayOf(1, 2, 3)
        assertTrue(ImageCrypto.isEncrypted(bytes))
    }

    @Test
    fun `isEncrypted returns false when bytes equal magic length exactly`() {
        // size must be strictly greater than MAGIC.size
        assertFalse(ImageCrypto.isEncrypted(magic))
    }

    @Test
    fun `isEncrypted returns false for plain jpeg header`() {
        val jpegHeader = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10)
        assertFalse(ImageCrypto.isEncrypted(jpegHeader))
    }

    @Test
    fun `isEncrypted returns false for empty byte array`() {
        assertFalse(ImageCrypto.isEncrypted(ByteArray(0)))
    }

    @Test
    fun `isEncrypted returns false when prefix partially matches magic`() {
        val partial = "REN".toByteArray(Charsets.US_ASCII) + byteArrayOf(1, 2, 3, 4)
        assertFalse(ImageCrypto.isEncrypted(partial))
    }

    @Test
    fun `isEncrypted returns false for bytes shorter than magic`() {
        assertFalse(ImageCrypto.isEncrypted(byteArrayOf(1, 2)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decrypt throws for empty byte array`() {
        ImageCrypto.decrypt(ByteArray(0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decrypt throws when data is exactly magic plus iv length`() {
        // require is bytes.size > MAGIC.size + IV_LEN (4 + 12 = 16), so size==16 must throw
        val data = magic + ByteArray(12)
        ImageCrypto.decrypt(data)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decrypt throws for data shorter than magic plus iv`() {
        val data = magic + ByteArray(5)
        ImageCrypto.decrypt(data)
    }

    @Test
    fun `decrypt validation message describes invalid encrypted image data`() {
        val ex = try {
            ImageCrypto.decrypt(ByteArray(3))
            null
        } catch (e: IllegalArgumentException) {
            e
        }
        assertEquals("Invalid encrypted image data", ex?.message)
    }
}
