package org.rite.hl7.builder.pharmacy.rxd

object RxdVersionCapabilities {

    /**
     * Maximum RXD field number supported by HL7 version.
     * Conservative values ensure backward compatibility.
     */
    private val maxFieldByVersion = mapOf(
        "2.1" to 20,
        "2.2" to 20,
        "2.3" to 20,
        "2.3.1" to 20,
        "2.4" to 20,
        "2.5" to 20,
        "2.5.1" to 20,
        "2.6" to 20,
        "2.7" to 20,
        "2.8" to 20
    )

    fun maxField(version: String): Int =
        maxFieldByVersion[version] ?: 20
}
