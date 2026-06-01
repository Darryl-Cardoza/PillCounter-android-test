package org.rite.hl7.builder.pharmacy.rxc

object RxcVersionCapabilities {

    fun maxField(version: String): Int =
        when {
            version.startsWith("2.1") -> 4
            version.startsWith("2.2") -> 4
            version.startsWith("2.3") -> 5
            else -> 6 // 2.4+
        }
}
