package org.rite.hl7.builder.header

object MshVersionCapabilities {

    private val maxFieldByVersion = mapOf(
        "2.1" to 12,
        "2.2" to 12,
        "2.3" to 12,
        "2.3.1" to 12,
        "2.4" to 16,
        "2.5" to 21,
        "2.5.1" to 21,
        "2.6" to 21,
        "2.7" to 21,
        "2.8" to 21
    )

    fun maxField(version: String): Int =
        maxFieldByVersion[version] ?: 12
}
