package org.rite.hl7.version

/**
 * Single registry of the maximum field count a segment carries per HL7 version.
 *
 * Used on the BUILD side to trim trailing fields beyond the version cap, and
 * available on the PARSE side for version-aware validation. Unknown segments
 * (Z-segments, vendor segments) have no cap.
 */
object SegmentCapabilities {

    // Per-segment breakpoints: ordered (version -> maxFields). Lookup walks
    // from the requested version downward to the nearest defined breakpoint.
    private val table: Map<String, List<Pair<HL7Version, Int>>> = mapOf(
        "MSH" to listOf(HL7Version.V21 to 12, HL7Version.V24 to 16, HL7Version.V25 to 21),
        "PID" to listOf(HL7Version.V21 to 30, HL7Version.V23 to 30, HL7Version.V25 to 39),
        "PV1" to listOf(HL7Version.V21 to 40, HL7Version.V22 to 44, HL7Version.V25 to 52),
        "ORC" to listOf(HL7Version.V21 to 19, HL7Version.V23 to 25, HL7Version.V25 to 31),
        "RXE" to listOf(HL7Version.V21 to 14, HL7Version.V22 to 16, HL7Version.V23 to 18, HL7Version.V24 to 20, HL7Version.V25 to 25),
        "RXD" to listOf(HL7Version.V21 to 20, HL7Version.V25 to 25),
        "RXC" to listOf(HL7Version.V21 to 4, HL7Version.V23 to 5, HL7Version.V24 to 9),
        "RXR" to listOf(HL7Version.V21 to 3, HL7Version.V25 to 6),
        "OBX" to listOf(HL7Version.V21 to 11, HL7Version.V22 to 17, HL7Version.V24 to 19, HL7Version.V25 to 25),
        "EQU" to listOf(HL7Version.V24 to 4),
        "INV" to listOf(HL7Version.V24 to 20),
        "NTE" to listOf(HL7Version.V21 to 3, HL7Version.V25 to 4),
        "MSA" to listOf(HL7Version.V21 to 6, HL7Version.V25 to 3),
        "ERR" to listOf(HL7Version.V21 to 1, HL7Version.V25 to 12),
        "QPD" to listOf(HL7Version.V25 to 60),
        "RCP" to listOf(HL7Version.V25 to 7),
        "QAK" to listOf(HL7Version.V25 to 8),
    )

    /** Max 1-based field index for [segment] at [version], or null if uncapped (e.g. Z-segments). */
    fun maxFields(segment: String, version: HL7Version): Int? {
        val breakpoints = table[segment] ?: return null
        var result: Int? = null
        for ((v, max) in breakpoints) {
            if (v.ordinal <= version.ordinal) result = max
        }
        // If requested version is below the first breakpoint, use the first.
        return result ?: breakpoints.firstOrNull()?.second
    }
}
