package com.dispensesure.retail.core.audit.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

class Sha256Test {

    @Test
    fun `matches the standard vectors`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hexOf("abc"))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hexOf(""))
    }

    @Test
    fun `always 64 lower-case hex characters`() {
        val hex = Sha256.hexOf(ByteArray(300_000) { it.toByte() })
        assertEquals(64, hex.length)
        assertEquals(hex.lowercase(), hex)
    }

    @Test
    fun `text is hashed as UTF-8`() {
        assertEquals(Sha256.hexOf("é".toByteArray(Charsets.UTF_8)), Sha256.hexOf("é"))
    }
}
