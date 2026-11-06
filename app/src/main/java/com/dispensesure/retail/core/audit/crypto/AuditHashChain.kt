package com.dispensesure.retail.core.audit.crypto

/** One chain per file: H0 = SHA-256(""), then Hn = SHA-256(hex(Hn-1) + line n). The last value is chain_last_hash. */
object AuditHashChain {
    val START_HASH: String = Sha256.hexOf("")

    fun lastHash(lines: List<String>): String =
        lines.fold(START_HASH) { previousHash, line -> Sha256.hexOf(previousHash + line) }
}
