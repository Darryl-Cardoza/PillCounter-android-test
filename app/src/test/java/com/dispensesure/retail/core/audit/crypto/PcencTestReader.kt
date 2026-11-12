package com.dispensesure.retail.core.audit.crypto

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Test-only PCENC reader, laid out like the backend's open. */
object PcencTestReader {
    fun open(sealed: ByteArray, dek: ByteArray): ByteArray {
        val headerLength = 11 + sealed[6].toInt()
        val nonce = sealed.copyOfRange(headerLength, headerLength + 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(sealed, 0, headerLength)
        return cipher.doFinal(sealed, headerLength + 12, sealed.size - headerLength - 12)
    }
}
