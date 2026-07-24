package com.rite.pillcounting.core.security

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class ModelDecryptorTest {

    private val magic = "RITE".toByteArray()
    private val nonceLen = 12
    private val tagLenBits = 128

    private fun randomKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    private fun buildEncryptedFile(
        plaintext: ByteArray,
        keyBytes: ByteArray,
        magicBytes: ByteArray = magic,
        nonce: ByteArray = ByteArray(nonceLen).also { SecureRandom().nextBytes(it) },
        corruptCipherText: Boolean = false
    ): File {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(keyBytes, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(tagLenBits, nonce))
        val cipherText = cipher.doFinal(plaintext)
        if (corruptCipherText) {
            cipherText[0] = (cipherText[0] + 1).toByte()
        }
        val file = File.createTempFile("model", ".enc")
        file.deleteOnExit()
        file.outputStream().use { out ->
            out.write(magicBytes)
            out.write(nonce)
            out.write(cipherText)
        }
        return file
    }

    private fun mockKeyUnit(keyBytes: ByteArray): ModelKeyUnit {
        val unit = mockk<ModelKeyUnit>()
        // material() returns a fresh copy each time it's called since production code fills it with zeros
        every { unit.material() } answers { keyBytes.copyOf() }
        return unit
    }

    @Test
    fun `decrypts valid file and returns original plaintext`() {
        val key = randomKey()
        val plaintext = "hello world model bytes".toByteArray()
        val file = buildEncryptedFile(plaintext, key)
        val unit = mockKeyUnit(key)

        val result = ModelDecryptor.decryptToBytes(file, unit)

        assertArrayEquals(plaintext, result)
    }

    @Test
    fun `decrypts empty plaintext successfully`() {
        val key = randomKey()
        val plaintext = ByteArray(0)
        val file = buildEncryptedFile(plaintext, key)
        val unit = mockKeyUnit(key)

        val result = ModelDecryptor.decryptToBytes(file, unit)

        assertEquals(0, result.size)
    }

    @Test
    fun `decrypts large plaintext successfully`() {
        val key = randomKey()
        val plaintext = ByteArray(1_000_000) { (it % 256).toByte() }
        val file = buildEncryptedFile(plaintext, key)
        val unit = mockKeyUnit(key)

        val result = ModelDecryptor.decryptToBytes(file, unit)

        assertArrayEquals(plaintext, result)
    }

    @Test
    fun `throws IllegalArgumentException when magic header is wrong`() {
        val key = randomKey()
        val file = buildEncryptedFile(
            "data".toByteArray(),
            key,
            magicBytes = "NOPE".toByteArray()
        )
        val unit = mockKeyUnit(key)

        try {
            ModelDecryptor.decryptToBytes(file, unit)
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("Invalid file format: missing RITE header", e.message)
        }
    }

    @Test
    fun `throws IllegalArgumentException when file is too short for magic header`() {
        val key = randomKey()
        val file = File.createTempFile("model_short", ".enc")
        file.deleteOnExit()
        file.outputStream().use { it.write(byteArrayOf(1, 2)) }
        val unit = mockKeyUnit(key)

        try {
            ModelDecryptor.decryptToBytes(file, unit)
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("Invalid file format: missing RITE header", e.message)
        }
    }

    @Test
    fun `throws IllegalArgumentException when nonce is incomplete`() {
        val key = randomKey()
        val file = File.createTempFile("model_shortnonce", ".enc")
        file.deleteOnExit()
        file.outputStream().use { out ->
            out.write(magic)
            out.write(byteArrayOf(1, 2, 3)) // only 3 bytes instead of 12
        }
        val unit = mockKeyUnit(key)

        try {
            ModelDecryptor.decryptToBytes(file, unit)
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("Incomplete nonce", e.message)
        }
    }

    @Test
    fun `throws IllegalStateException when wrong key is used for decryption`() {
        val correctKey = randomKey()
        val wrongKey = randomKey()
        val file = buildEncryptedFile("secret data".toByteArray(), correctKey)
        val unit = mockKeyUnit(wrongKey)

        try {
            ModelDecryptor.decryptToBytes(file, unit)
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("Decryption failed: wrong key or corrupted file", e.message)
            assertTrue(e.cause != null)
        }
    }

    @Test
    fun `throws IllegalStateException when cipher text is corrupted`() {
        val key = randomKey()
        val file = buildEncryptedFile("some payload".toByteArray(), key, corruptCipherText = true)
        val unit = mockKeyUnit(key)

        try {
            ModelDecryptor.decryptToBytes(file, unit)
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("Decryption failed: wrong key or corrupted file", e.message)
        }
    }

    @Test
    fun `throws IllegalStateException when ciphertext is empty (missing auth tag)`() {
        val key = randomKey()
        val file = File.createTempFile("model_notag", ".enc")
        file.deleteOnExit()
        file.outputStream().use { out ->
            out.write(magic)
            out.write(ByteArray(nonceLen))
            // no ciphertext / tag bytes at all
        }
        val unit = mockKeyUnit(key)

        try {
            ModelDecryptor.decryptToBytes(file, unit)
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("Decryption failed: wrong key or corrupted file", e.message)
        }
    }

    @Test
    fun `zeroes out key bytes returned by material after use`() {
        val key = randomKey()
        val originalKeyCopy = key.copyOf()
        val plaintext = "zero key test".toByteArray()
        val file = buildEncryptedFile(plaintext, key)

        // Use the real key array directly (not a copy) so we can inspect it post-call.
        val unit = mockk<ModelKeyUnit>()
        every { unit.material() } returns key

        val result = ModelDecryptor.decryptToBytes(file, unit)

        assertArrayEquals(plaintext, result)
        assertTrue("key bytes should be zeroed after decryption", key.all { it == 0.toByte() })
        assertTrue(!key.contentEquals(originalKeyCopy))
    }

    @Test
    fun `propagates exception thrown by material`() {
        val file = buildEncryptedFile("data".toByteArray(), randomKey())
        val unit = mockk<ModelKeyUnit>()
        every { unit.material() } throws IllegalStateException("ModelKeyUnit: payload not found in store")

        try {
            ModelDecryptor.decryptToBytes(file, unit)
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("ModelKeyUnit: payload not found in store", e.message)
        }
    }
}
