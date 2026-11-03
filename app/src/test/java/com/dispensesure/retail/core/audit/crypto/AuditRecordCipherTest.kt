package com.dispensesure.retail.core.audit.crypto

import com.dispensesure.retail.core.audit.AuditLogConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.spec.SecretKeySpec

class AuditRecordCipherTest {

    private val testKey = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val otherKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    private val cipher = AuditRecordCipher { testKey }

    @Test
    fun `encrypted line decrypts back to the same json`() {
        val recordJson = """{"tag":"LOGIN_SUCCEEDED"}"""
        assertEquals(recordJson, cipher.decrypt(cipher.encrypt(recordJson)))
    }

    @Test
    fun `line starts with the version prefix and hides the plaintext`() {
        val line = cipher.encrypt("""{"tag":"LOGIN_SUCCEEDED"}""")
        assertTrue(line.startsWith(AuditLogConfig.LINE_PREFIX))
        assertTrue(!line.contains("LOGIN_SUCCEEDED"))
    }

    @Test
    fun `same json encrypts to different lines`() {
        assertNotEquals(cipher.encrypt("{}"), cipher.encrypt("{}"))
    }

    @Test(expected = Exception::class)
    fun `tampered line fails to decrypt`() {
        val payload = Base64.getDecoder().decode(cipher.encrypt("{}").removePrefix(AuditLogConfig.LINE_PREFIX))
        payload[payload.lastIndex] = (payload.last().toInt() xor 1).toByte()
        cipher.decrypt(AuditLogConfig.LINE_PREFIX + Base64.getEncoder().encodeToString(payload))
    }

    @Test(expected = Exception::class)
    fun `line from another key fails to decrypt`() {
        AuditRecordCipher { otherKey }.decrypt(cipher.encrypt("{}"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `line without the version prefix is rejected`() {
        cipher.decrypt("garbage")
    }

    @Test
    fun `isReadable is false for a cut-off line`() {
        assertFalse(cipher.isReadable(cipher.encrypt("{}").dropLast(10)))
    }

    @Test
    fun `isReadable throws when the key cannot load`() {
        assertThrows(IllegalStateException::class.java) {
            AuditRecordCipher { error("keystore") }.isReadable("v1:AAAA")
        }
    }
}
