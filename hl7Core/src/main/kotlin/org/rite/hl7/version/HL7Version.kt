package org.rite.hl7.version

/**
 * HL7 v2.x versions this library understands. [DEFAULT] is used when MSH-12 is
 * absent or unrecognized.
 */
enum class HL7Version(val wire: String) {
    V21("2.1"),
    V22("2.2"),
    V23("2.3"),
    V231("2.3.1"),
    V24("2.4"),
    V25("2.5"),
    V251("2.5.1"),
    V26("2.6"),
    V27("2.7"),
    V271("2.7.1"),
    V28("2.8");

    companion object {
        val DEFAULT = V25

        /** Resolves an MSH-12 value (e.g. "2.5", "2.5.1") to a version, defaulting to [DEFAULT]. */
        fun from(msh12: String?): HL7Version {
            val v = msh12?.trim()?.takeIf { it.isNotEmpty() } ?: return DEFAULT
            return entries.firstOrNull { it.wire == v }
                ?: entries.firstOrNull { v.startsWith(it.wire) }
                ?: DEFAULT
        }
    }
}
