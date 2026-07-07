package org.rite.hl7.model.ast

import org.rite.hl7.encoding.HL7Delimiters

/**
 * One field of an HL7 segment. A field may have multiple repetitions
 * (separated by `~` on the wire); each repetition is a list of components.
 *
 * Most callers only ever touch the first repetition; convenience accessors
 * ([component], [value]) operate on it. All indices are 1-based.
 */
class HL7Field(val repetitions: List<List<HL7Component>>) {

    /** Repetition [r] (1-based) as a list of components, or empty list if absent. */
    fun repetition(r: Int): List<HL7Component> = repetitions.getOrNull(r - 1) ?: emptyList()

    /** Components of the first repetition. */
    val first: List<HL7Component> get() = repetitions.firstOrNull() ?: emptyList()

    /** Component [c] (1-based) of the first repetition, or [HL7Component.EMPTY]. */
    fun component(c: Int): HL7Component = first.getOrNull(c - 1) ?: HL7Component.EMPTY

    /** Component [c] (1-based) of repetition [r] (1-based). */
    fun component(r: Int, c: Int): HL7Component = repetition(r).getOrNull(c - 1) ?: HL7Component.EMPTY

    /** First component, first subcomponent of the first repetition — the plainest value. */
    val value: String get() = component(1).value

    val isEmpty: Boolean get() = repetitions.all { rep -> rep.all { it.isEmpty } }

    /** Serializes this field back to wire text. */
    fun encode(d: HL7Delimiters): String =
        repetitions.joinToString(d.repetition.toString()) { rep ->
            rep.joinToString(d.component.toString()) { it.encode(d) }
        }

    companion object {
        val EMPTY = HL7Field(emptyList())

        /** A field holding a single plain value (one repetition, one component, one subcomponent). */
        fun of(value: String): HL7Field = HL7Field(listOf(listOf(HL7Component(listOf(value)))))

        /** Parses one field's wire text into repetitions → components → subcomponents. */
        fun parse(raw: String, d: HL7Delimiters): HL7Field {
            val reps = raw.split(d.repetition).map { rep ->
                rep.split(d.component).map { HL7Component.parse(it, d) }
            }
            return HL7Field(reps)
        }
    }
}
