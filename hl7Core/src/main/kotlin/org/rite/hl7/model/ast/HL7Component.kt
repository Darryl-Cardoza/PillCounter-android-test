package org.rite.hl7.model.ast

import org.rite.hl7.encoding.HL7Delimiters
import org.rite.hl7.encoding.HL7Escaping

/**
 * One component of an HL7 field. A component is a list of subcomponents
 * (separated by `&` on the wire). Stored values are already unescaped.
 *
 * All accessors are 1-based to match HL7 spec numbering.
 */
class HL7Component(val subcomponents: List<String>) {

    /** Value of subcomponent [n] (1-based), or empty string if absent. */
    fun subcomponent(n: Int): String = subcomponents.getOrNull(n - 1) ?: ""

    /** First subcomponent — the common case when a component is a plain value. */
    val value: String get() = subcomponents.firstOrNull() ?: ""

    val isEmpty: Boolean get() = subcomponents.all { it.isEmpty() }

    /** Serializes this component back to wire text (escaping each subcomponent). */
    fun encode(d: HL7Delimiters): String =
        subcomponents.joinToString(d.subcomponent.toString()) { HL7Escaping.escape(it, d) }

    companion object {
        val EMPTY = HL7Component(emptyList())

        /** Parses one component's wire text into subcomponents (unescaping each). */
        fun parse(raw: String, d: HL7Delimiters): HL7Component =
            HL7Component(raw.split(d.subcomponent).map { HL7Escaping.unescape(it, d) })
    }
}
