package com.dispensesure.retail.core.audit.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

class HkdfTest {

    private fun hex(text: String): ByteArray =
        text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun `matches RFC 5869 test case 1`() {
        val outputKeyMaterial = Hkdf.deriveSha256(
            inputKeyMaterial = ByteArray(22) { 0x0b },
            salt = hex("000102030405060708090a0b0c"),
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            outputLengthBytes = 42
        )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            outputKeyMaterial.toHex()
        )
    }

    @Test
    fun `no salt derives the same key every time`() {
        val first = Hkdf.deriveSha256(ByteArray(32) { 1 }, salt = null, info = "x".toByteArray(), outputLengthBytes = 32)
        val second = Hkdf.deriveSha256(ByteArray(32) { 1 }, salt = null, info = "x".toByteArray(), outputLengthBytes = 32)
        assertEquals(first.toHex(), second.toHex())
    }
}
