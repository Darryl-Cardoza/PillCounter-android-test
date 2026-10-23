package com.dispensesure.retail.core.security

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** PCWRP key envelope from the backend guide §1.4: `PCWRP` ‖ 1 ‖ nonce ‖ ciphertext+tag. AAD is the 6-byte header. */
internal object Pcwrp {
    private val HEADER = "PCWRP".toByteArray(Charsets.US_ASCII) + byteArrayOf(1)
    private const val NONCE_LENGTH_BYTES = 12
    private const val TAG_LENGTH_BITS = 128

    // Keystore keys pick their own nonce, so it is read back from the cipher.
    fun seal(key: SecretKey, plainBytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val nonce = cipher.iv
        check(nonce.size == NONCE_LENGTH_BYTES) { "Unexpected nonce length" }
        cipher.updateAAD(HEADER)
        return HEADER + nonce + cipher.doFinal(plainBytes)
    }

    fun open(key: SecretKey, wrapped: ByteArray): ByteArray {
        val nonceEnd = HEADER.size + NONCE_LENGTH_BYTES
        require(wrapped.size > nonceEnd && wrapped.copyOfRange(0, HEADER.size).contentEquals(HEADER)) {
            "Not a PCWRP envelope"
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, wrapped.copyOfRange(HEADER.size, nonceEnd)))
        cipher.updateAAD(HEADER)
        return cipher.doFinal(wrapped, nonceEnd, wrapped.size - nonceEnd)
    }
}
