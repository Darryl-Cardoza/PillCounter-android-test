package org.rite.hl7.util

/**
 * Timestamp helpers for HL7 builders. [now] returns the current local time
 * formatted as `yyyyMMddHHmmss` (HL7 TS format).
 *
 * The platform [currentLocalDateTime] returns ISO `yyyy-MM-dd'T'HH:mm:ss`;
 * we strip the separators to the HL7 form here so no platform code changes.
 */
object HL7Date {
    /** Current local time as `yyyyMMddHHmmss`. */
    fun now(): String = toHl7(currentLocalDateTime())

    /** Converts ISO `yyyy-MM-dd'T'HH:mm:ss` to HL7 `yyyyMMddHHmmss`. */
    fun toHl7(iso: String): String =
        iso.filter { it.isDigit() }.take(14)
}
