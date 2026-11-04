package com.dispensesure.retail.core.audit.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HKDF with HMAC-SHA256 (RFC 5869): derives a sub-key from a root key. */
object Hkdf {
    private const val HMAC_SHA256 = "HmacSHA256"
    private const val HASH_LENGTH_BYTES = 32

    fun deriveSha256(
        inputKeyMaterial: ByteArray,
        salt: ByteArray?,
        info: ByteArray,
        outputLengthBytes: Int
    ): ByteArray {
        require(outputLengthBytes in 1..255 * HASH_LENGTH_BYTES) { "Invalid HKDF output length" }

        // No salt = a zero-filled salt, as the RFC defines.
        val extractSalt = salt?.takeIf { it.isNotEmpty() } ?: ByteArray(HASH_LENGTH_BYTES)
        val pseudoRandomKey = Mac.getInstance(HMAC_SHA256).run {
            init(SecretKeySpec(extractSalt, HMAC_SHA256))
            doFinal(inputKeyMaterial)
        }

        val expandMac = Mac.getInstance(HMAC_SHA256).apply { init(SecretKeySpec(pseudoRandomKey, HMAC_SHA256)) }
        val outputKeyMaterial = ByteArray(outputLengthBytes)
        var previousBlock = ByteArray(0)
        var bytesWritten = 0
        var blockCounter = 1
        while (bytesWritten < outputLengthBytes) {
            expandMac.update(previousBlock)
            expandMac.update(info)
            expandMac.update(blockCounter.toByte())
            previousBlock = expandMac.doFinal()
            val bytesToCopy = minOf(previousBlock.size, outputLengthBytes - bytesWritten)
            System.arraycopy(previousBlock, 0, outputKeyMaterial, bytesWritten, bytesToCopy)
            bytesWritten += bytesToCopy
            blockCounter++
        }
        return outputKeyMaterial
    }
}
