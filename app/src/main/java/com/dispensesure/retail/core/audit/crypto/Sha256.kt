package com.dispensesure.retail.core.audit.crypto

import java.security.MessageDigest

/** SHA-256 as 64 lower-case hex characters. Text is hashed as UTF-8. */
object Sha256 {
    fun hexOf(text: String): String = hexOf(text.toByteArray(Charsets.UTF_8))

    fun hexOf(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
