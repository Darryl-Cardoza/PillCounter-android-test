package com.dispensesure.retail.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class PcwrpTest {

    private val rawKek = ByteArray(32) { it.toByte() }
    private val kek = SecretKeySpec(rawKek, "AES")
    private val dek = ByteArray(32) { 9 }

    // The backend's open (guide §1.4): AAD is bytes 0..5, nonce is bytes 6..17.
    private fun backendOpen(wrapped: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(rawKek, "AES"), GCMParameterSpec(128, wrapped.copyOfRange(6, 18)))
        cipher.updateAAD(wrapped, 0, 6)
        return cipher.doFinal(wrapped, 18, wrapped.size - 18)
    }

    // The guide's wrapDek (§1.4), without the Base64.
    private fun guideWrapDek(): ByteArray {
        val header = "PCWRP".toByteArray(Charsets.US_ASCII) + byteArrayOf(1)
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(rawKek, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(header)
        return header + nonce + cipher.doFinal(dek)
    }

    @Test
    fun `a 32-byte dek wraps to 66 bytes behind the PCWRP header`() {
        val wrapped = Pcwrp.seal(kek, dek)
        assertEquals(66, wrapped.size)
        assertArrayEquals("PCWRP".toByteArray(Charsets.US_ASCII) + byteArrayOf(1), wrapped.copyOfRange(0, 6))
    }

    @Test
    fun `the backend can open what we wrap`() {
        assertArrayEquals(dek, backendOpen(Pcwrp.seal(kek, dek)))
    }

    @Test
    fun `we can open what the guide's code wraps`() {
        assertArrayEquals(dek, Pcwrp.open(kek, guideWrapDek()))
    }

    @Test
    fun `each wrap uses a new nonce`() {
        assertFalse(Pcwrp.seal(kek, dek).contentEquals(Pcwrp.seal(kek, dek)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `bytes without the PCWRP header are refused`() {
        Pcwrp.open(kek, ByteArray(60))
    }

    @Test(expected = AEADBadTagException::class)
    fun `a changed byte fails the tag check`() {
        val wrapped = Pcwrp.seal(kek, dek)
        wrapped[wrapped.size - 1] = (wrapped[wrapped.size - 1] + 1).toByte()
        Pcwrp.open(kek, wrapped)
    }
}
