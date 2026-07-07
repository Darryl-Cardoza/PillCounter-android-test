package org.rite.hl7.model

import org.rite.hl7.encoding.HL7Delimiters
import org.rite.hl7.encoding.Mllp
import org.rite.hl7.model.ast.HL7Segment
import org.rite.hl7.model.segment.MSHSegment
import org.rite.hl7.version.HL7Version

/**
 * A parsed HL7 message: an ordered list of typed segments plus the delimiters
 * and version they were parsed with. Segment order is preserved exactly as
 * received, so the message re-serializes losslessly.
 */
class HL7Message internal constructor(
    val typedSegments: List<TypedSegment>,
    val delimiters: HL7Delimiters,
    val version: HL7Version,
) {
    /** The MSH header (always first), or null if somehow absent. */
    val header: MSHSegment? get() = typedSegments.firstOrNull() as? MSHSegment

    val messageType: String get() = header?.messageType ?: ""
    val messageCode: String get() = header?.messageCode ?: ""
    val triggerEvent: String get() = header?.triggerEvent ?: ""
    val messageControlId: String get() = header?.messageControlId ?: ""
    val sendingFacility: String get() = header?.sendingFacility ?: ""

    /** Business classification (DISPENSE, INVENTORY_ADJUSTMENT, QUERY, …). */
    val kind: HL7MessageKind get() = HL7MessageKind.from(this)

    /** All typed segments with the given name, in order. */
    fun segmentsNamed(name: String): List<TypedSegment> =
        typedSegments.filter { it.segmentName == name }

    /** First typed segment with the given name, or null. */
    fun segmentNamed(name: String): TypedSegment? =
        typedSegments.firstOrNull { it.segmentName == name }

    /**
     * First segment of type [T] with the given [name], or null.
     * Kotlin: `message.segment<RXDSegment>("RXD")`.
     */
    inline fun <reified T : TypedSegment> segment(name: String): T? =
        segmentNamed(name) as? T

    /** All segments of type [T] with the given [name]. */
    inline fun <reified T : TypedSegment> segments(name: String): List<T> =
        segmentsNamed(name).filterIsInstance<T>()

    /** Re-serializes the message to wire text (segments joined by CR). */
    fun encode(): String =
        typedSegments.joinToString("\r") { it.raw.encode(delimiters) }

    /** Re-serializes and wraps in MLLP framing for TCP transport. */
    fun encodeMllp(): ByteArray = Mllp.wrap(encode())

    /** The underlying generic AST segments (lossless). */
    val rawSegments: List<HL7Segment> get() = typedSegments.map { it.raw }
}
