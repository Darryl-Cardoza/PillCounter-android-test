package com.dispensesure.retail.core.audit.crypto

import com.dispensesure.retail.core.audit.AuditLogConfig
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/** Encrypts one audit record into one file line, and back. Each line has its own random IV. */
class AuditRecordCipher(private val keySource: AuditKeySource) {

    private val secureRandom = SecureRandom()

    fun encrypt(recordJson: String): String {
        val iv = ByteArray(AuditLogConfig.IV_LENGTH_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keySource.key(), GCMParameterSpec(AuditLogConfig.TAG_LENGTH_BITS, iv))
        val cipherText = cipher.doFinal(recordJson.toByteArray(Charsets.UTF_8))
        return AuditLogConfig.LINE_PREFIX + Base64.getEncoder().encodeToString(iv + cipherText)
    }

    fun decrypt(line: String): String {
        require(line.startsWith(AuditLogConfig.LINE_PREFIX)) { "Unknown audit line format" }
        val payload = Base64.getDecoder().decode(line.removePrefix(AuditLogConfig.LINE_PREFIX))
        val iv = payload.copyOfRange(0, AuditLogConfig.IV_LENGTH_BYTES)
        val cipherText = payload.copyOfRange(AuditLogConfig.IV_LENGTH_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keySource.key(), GCMParameterSpec(AuditLogConfig.TAG_LENGTH_BITS, iv))
        return String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
