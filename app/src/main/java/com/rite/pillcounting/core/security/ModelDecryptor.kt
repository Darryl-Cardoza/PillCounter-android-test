package com.rite.pillcounting.core.security

import java.io.File
import java.io.FileInputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object ModelDecryptor {
    private const val MAGIC = "RITE"
    private const val NONCE_LEN = 12
    private const val TAG_LEN_BITS = 128

    fun decryptToBytes(inFile: File, modelKeyUnit: ModelKeyUnit): ByteArray {
        val keyBytes = modelKeyUnit.material()

        FileInputStream(inFile).use { fis ->
            val magicBytes = ByteArray(4)
            require(fis.read(magicBytes) == 4 && magicBytes.contentEquals(MAGIC.toByteArray())) {
                "Invalid file format: missing RITE header"
            }
            val nonce = ByteArray(NONCE_LEN)
            require(fis.read(nonce) == NONCE_LEN) { "Incomplete nonce" }
            val cipherText = fis.readBytes()

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(keyBytes, "AES")
            val gcmSpec = GCMParameterSpec(TAG_LEN_BITS, nonce)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

            // Zero out key bytes immediately after use
            keyBytes.fill(0)

            return try {
                cipher.doFinal(cipherText)
            } catch (e: Exception) {
                throw IllegalStateException("Decryption failed: wrong key or corrupted file", e)
            }
        }
    }
}