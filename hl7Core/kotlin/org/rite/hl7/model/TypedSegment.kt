package org.rite.hl7.model

import org.rite.hl7.model.ast.HL7Component
import org.rite.hl7.model.ast.HL7Segment

/**
 * Base class for all typed segment views. A typed segment is a thin wrapper over
 * a generic [HL7Segment]; its properties are computed getters over fixed 1-based
 * field indices. Every value is a plain [String] — date/enum conversion belongs
 * in the service layer, not here.
 *
 * Subclasses expose named getters like:
 * ```
 * val lotNumber: String get() = fieldValue(15)
 * ```
 */
abstract class TypedSegment(val raw: HL7Segment) {

    /** Segment name (e.g. "MSH", "RXD"). */
    val segmentName: String get() = raw.name

    /** Highest 1-based field index present — lets callers distinguish same-named dialects by shape. */
    val fieldCount: Int get() = raw.fieldCount

    /** Plain value of field [n] (1-based). */
    protected fun fieldValue(n: Int): String = raw.fieldValue(n)

    /** Plain value of component [c] (1-based) within field [n] (1-based). */
    protected fun component(n: Int, c: Int): String = raw.componentValue(n, c)

    /** Plain value of subcomponent [s] within component [c] of field [n] (all 1-based). */
    protected fun subcomponent(n: Int, c: Int, s: Int): String =
        raw.field(n).component(c).subcomponent(s)

    /** Raw [HL7Component] for advanced access. */
    protected fun componentOf(n: Int, c: Int): HL7Component = raw.field(n).component(c)

    /** Plain value of the first component of every repetition (`~`-separated) of field [n]. */
    protected fun repetitions(n: Int): List<String> = raw.field(n).repetitions.map { it.firstOrNull()?.value ?: "" }
}
