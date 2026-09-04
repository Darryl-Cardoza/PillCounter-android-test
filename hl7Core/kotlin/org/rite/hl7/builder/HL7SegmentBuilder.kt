package org.rite.hl7.builder

import org.rite.hl7.encoding.HL7Delimiters
import org.rite.hl7.model.ast.HL7Component
import org.rite.hl7.model.ast.HL7Field
import org.rite.hl7.model.ast.HL7Segment
import org.rite.hl7.version.HL7Version
import org.rite.hl7.version.SegmentCapabilities

/**
 * Base class for typed segment builders. Subclasses expose named `var`
 * properties that write plain [String]s into 1-based (field, component) slots.
 * [build] assembles a generic [HL7Segment], trimming trailing empty fields per
 * the segment's version cap.
 */
abstract class HL7SegmentBuilder(val name: String) {

    // (fieldIndex, componentIndex) -> value, all 1-based.
    private val cells: MutableMap<Int, MutableMap<Int, String>> = mutableMapOf()

    // fieldIndex -> repetition values (component 1 each), for `~`-repeated fields.
    private val repeatedCells: MutableMap<Int, List<String>> = mutableMapOf()

    /** Sets the plain value of field [n] (component 1). */
    protected fun set(n: Int, value: String?) {
        if (value == null) return
        cells.getOrPut(n) { mutableMapOf() }[1] = value
    }

    /** Sets component [c] of field [n]. */
    protected fun set(n: Int, c: Int, value: String?) {
        if (value == null) return
        cells.getOrPut(n) { mutableMapOf() }[c] = value
    }

    /** Sets field [n] as multiple `~`-separated repetitions (e.g. a list of image paths). */
    protected fun setRepeated(n: Int, values: List<String>?) {
        if (values.isNullOrEmpty()) return
        repeatedCells[n] = values
    }

    /** Reads back the plain value of field [n] (component 1), or "". */
    protected fun get(n: Int): String = cells[n]?.get(1) ?: ""

    /** Reads back component [c] of field [n], or "". */
    protected fun get(n: Int, c: Int): String = cells[n]?.get(c) ?: ""

    /**
     * Flushes the subclass's named properties into the (field, component) cells.
     * Called automatically by [build]. MSH overrides [build] entirely and does
     * not use this hook.
     */
    protected open fun apply() {}

    /** Builds the generic segment for the given delimiters and version. */
    open fun build(delimiters: HL7Delimiters, version: HL7Version): HL7Segment {
        apply()
        val maxFieldIndex = maxOf(cells.keys.maxOrNull() ?: 0, repeatedCells.keys.maxOrNull() ?: 0)
        val cap = SegmentCapabilities.maxFields(name, version)
        val limit = if (cap != null) minOf(maxFieldIndex, cap) else maxFieldIndex

        // fields[0] is the segment name; fields[n] is HL7 field n (matches HL7Segment).
        val fields = ArrayList<HL7Field>(limit + 1)
        fields += HL7Field.of(name)
        for (n in 1..limit) {
            val reps = repeatedCells[n]
            val comps = cells[n]
            if (reps != null) {
                fields += HL7Field(reps.map { listOf(HL7Component(listOf(it))) })
            } else if (comps.isNullOrEmpty()) {
                fields += HL7Field.EMPTY
            } else {
                val maxComp = comps.keys.maxOrNull() ?: 0
                val components = (1..maxComp).map { c -> HL7Component(listOf(comps[c] ?: "")) }
                fields += HL7Field(listOf(components))
            }
        }
        return HL7Segment(name, fields, delimiters)
    }
}
