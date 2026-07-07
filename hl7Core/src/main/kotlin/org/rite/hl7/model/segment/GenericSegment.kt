package org.rite.hl7.model.segment

import org.rite.hl7.model.TypedSegment
import org.rite.hl7.model.ast.HL7Segment

/**
 * Lossless fallback for any segment without a registered typed view (unknown
 * Z-segments, vendor segments). Exposes generic 1-based field access and
 * re-serializes byte-for-byte through the same machinery as typed segments.
 */
class GenericSegment(raw: HL7Segment) : TypedSegment(raw) {
    /** Plain value of field [n] (1-based). Public generic accessor. */
    fun value(n: Int): String = fieldValue(n)

    /** Plain value of component [c] within field [n] (1-based). */
    fun value(n: Int, c: Int): String = component(n, c)
}
