package com.dispensesure.retail.core.audit.crypto

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** PCENC file envelope from the backend guide §2.3: header ‖ nonce ‖ ciphertext+tag. AAD is the whole header. */
object Pcenc {
    private val MAGIC = "PCENC".toByteArray(Charsets.US_ASCII)
    private const val ENVELOPE_VERSION: Byte = 1
    private const val NONCE_LENGTH_BYTES = 12
    private const val TAG_LENGTH_BITS = 128
    private const val MAX_KEY_ID_BYTES = 64
    private const val MAX_DEK_VERSION = 999_999

    fun seal(
        plaintext: ByteArray,
        dek: ByteArray,
        keyId: String,
        dekVersion: Int,
        secureRandom: SecureRandom = SecureRandom()
    ): ByteArray {
        val keyIdBytes = keyId.toByteArray(Charsets.US_ASCII)
        require(keyIdBytes.size in 1..MAX_KEY_ID_BYTES) { "dek_key_id must be 1 to $MAX_KEY_ID_BYTES bytes" }
        require(dekVersion in 1..MAX_DEK_VERSION) { "dek_version out of range" }
        val header = ByteBuffer.allocate(MAGIC.size + 2 + keyIdBytes.size + Int.SIZE_BYTES)
            .put(MAGIC)
            .put(ENVELOPE_VERSION)
            .put(keyIdBytes.size.toByte())
            .put(keyIdBytes)
            .putInt(dekVersion) // ByteBuffer is big-endian
            .array()
        val nonce = ByteArray(NONCE_LENGTH_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(TAG_LENGTH_BITS, nonce))
        cipher.updateAAD(header)
        return header + nonce + cipher.doFinal(plaintext)
    }
}
