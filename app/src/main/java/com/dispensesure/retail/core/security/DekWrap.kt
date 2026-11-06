package com.dispensesure.retail.core.security

/** How the DB DEK is stored right now: its PCWRP bytes and the KEK they're wrapped under. */
class DekWrap(
    val wrappedDek: ByteArray,
    val kekKeyId: String,
    val kekVersion: Int,
    val kekAlgorithm: String
)
