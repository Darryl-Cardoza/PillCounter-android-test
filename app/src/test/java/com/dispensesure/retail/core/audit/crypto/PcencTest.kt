package com.dispensesure.retail.core.audit.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.AEADBadTagException

class PcencTest {

    private val dek = ByteArray(32) { it.toByte() }
    private val plaintext = "hi".toByteArray()

    @Test
    fun `header follows the guide layout`() {
        val sealed = Pcenc.seal(plaintext, dek, "dek-1", 3)
        assertArrayEquals("PCENC".toByteArray(Charsets.US_ASCII), sealed.copyOfRange(0, 5))
        assertEquals(1, sealed[5].toInt())
        assertEquals(5, sealed[6].toInt())
        assertArrayEquals("dek-1".toByteArray(Charsets.US_ASCII), sealed.copyOfRange(7, 12))
        assertArrayEquals(byteArrayOf(0, 0, 0, 3), sealed.copyOfRange(12, 16))
        // header 16 + nonce 12 + plaintext 2 + tag 16
        assertEquals(46, sealed.size)
    }

    @Test
    fun `version is four big-endian bytes`() {
        assertArrayEquals(byteArrayOf(0, 0, 1, 0), Pcenc.seal(plaintext, dek, "dek-1", 256).copyOfRange(12, 16))
        assertArrayEquals(byteArrayOf(0, 0x0F, 0x42, 0x3F), Pcenc.seal(plaintext, dek, "dek-1", 999_999).copyOfRange(12, 16))
    }

    @Test
    fun `round trip with the dek`() {
        assertEquals("hello", String(PcencTestReader.open(Pcenc.seal("hello".toByteArray(), dek, "dek-1", 1), dek)))
    }

    @Test
    fun `editing the key id breaks the tag`() {
        val sealed = Pcenc.seal(plaintext, dek, "dek-1", 1)
        sealed[7] = 'x'.code.toByte()
        assertThrows(AEADBadTagException::class.java) { PcencTestReader.open(sealed, dek) }
    }

    @Test
    fun `two seals use different nonces`() {
        val first = Pcenc.seal(plaintext, dek, "dek-1", 1).copyOfRange(16, 28)
        val second = Pcenc.seal(plaintext, dek, "dek-1", 1).copyOfRange(16, 28)
        assertFalse(first.contentEquals(second))
    }

    @Test
    fun `out of range version or key id is refused`() {
        assertThrows(IllegalArgumentException::class.java) { Pcenc.seal(plaintext, dek, "dek-1", 0) }
        assertThrows(IllegalArgumentException::class.java) { Pcenc.seal(plaintext, dek, "dek-1", 1_000_000) }
        assertThrows(IllegalArgumentException::class.java) { Pcenc.seal(plaintext, dek, "", 1) }
        assertThrows(IllegalArgumentException::class.java) { Pcenc.seal(plaintext, dek, "a".repeat(65), 1) }
    }
}
