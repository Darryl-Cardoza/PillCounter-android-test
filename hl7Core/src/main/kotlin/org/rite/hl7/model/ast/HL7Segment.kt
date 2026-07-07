package org.rite.hl7.model.ast

import org.rite.hl7.encoding.HL7Delimiters

/**
 * A generic, fully-parsed HL7 segment: a name plus a list of [HL7Field]s.
 * This is the lossless representation every typed segment is projected from.
 *
 * Field access is 1-based to match HL7 numbering. The MSH-1 quirk (where field
 * 1 is the field separator itself and field 2 is the encoding characters) is
 * handled in exactly one place — here — so no other code needs to know about it.
 *
 * @param name        3-char segment name (e.g. "MSH", "RXD", "ZSN")
 * @param fields      parsed fields, indexed from MSH-2 / segment-1 onward
 * @param delimiters  the delimiter set this segment was parsed with (used for re-encode)
 */
class HL7Segment(
    val name: String,
    val fields: List<HL7Field>,
    val delimiters: HL7Delimiters = HL7Delimiters.DEFAULT,
) {
    private val isMsh: Boolean get() = name == "MSH"

    /**
     * Returns field [n] (1-based).
     *
     * For MSH, field 1 is the field separator and field 2 is the encoding
     * characters; both are synthesized so callers can read MSH-1..MSH-n
     * uniformly with every other segment.
     */
    fun field(n: Int): HL7Field {
        if (n < 1) return HL7Field.EMPTY
        if (isMsh) {
            return when (n) {
                1 -> HL7Field.of(delimiters.field.toString())
                else -> fields.getOrNull(n - 2) ?: HL7Field.EMPTY  // MSH-2 == fields[0]
            }
        }
        // Non-MSH: fields[0] is the segment name, so field(1) == fields[1].
        return fields.getOrNull(n) ?: HL7Field.EMPTY
    }

    /** Plain value of field [n] (first repetition, first component, first subcomponent). */
    fun fieldValue(n: Int): String = field(n).value

    /** Component [c] (1-based) of field [n] (1-based). */
    fun component(n: Int, c: Int): HL7Component = field(n).component(c)

    /** Plain value of component [c] of field [n]. */
    fun componentValue(n: Int, c: Int): String = field(n).component(c).value

    /** The highest 1-based field index present in this segment. */
    val fieldCount: Int get() = if (isMsh) fields.size + 1 else fields.size - 1

    /** Re-serializes this segment to wire text (name + fields joined by the field separator). */
    fun encode(d: HL7Delimiters = delimiters): String {
        if (isMsh) {
            // MSH|<enc>|<MSH-3>|...  — name, separator, encoding chars are literal.
            val rest = fields.drop(1)  // fields[0] is MSH-2 (encoding chars); re-emit literally
            val sb = StringBuilder()
            sb.append(name).append(d.field).append(d.encodingCharacters)
            for (f in rest) sb.append(d.field).append(f.encode(d))
            return trimTrailing(sb.toString(), d)
        }
        val sb = StringBuilder(name)
        for (i in 1 until fields.size) sb.append(d.field).append(fields[i].encode(d))
        return trimTrailing(sb.toString(), d)
    }

    private fun trimTrailing(s: String, d: HL7Delimiters): String = s.trimEnd(d.field)

    companion object {
        /**
         * Parses a single segment line (already stripped of the terminator)
         * into a generic [HL7Segment]. For MSH, the field separator and encoding
         * characters are consumed so that fields[0] holds MSH-2.
         */
        fun parse(line: String, d: HL7Delimiters): HL7Segment {
            val name = line.take(3)
            if (name == "MSH") {
                // line = MSH|^~\&|f3|f4...  → split off "MSH" + separator, keep encoding + rest.
                // After "MSH" + field-sep, the remainder split on field-sep gives:
                //   [encodingChars, f3, f4, ...]  → store as fields[0..]
                val afterName = line.drop(4)  // drop "MSH" + field separator
                val parts = afterName.split(d.field)
                // parts[0] = encoding characters (stored literally, not re-parsed for delimiters)
                val fields = parts.mapIndexed { idx, raw ->
                    if (idx == 0) HL7Field.of(raw) else HL7Field.parse(raw, d)
                }
                return HL7Segment(name, fields, d)
            }
            val parts = line.split(d.field)
            // parts[0] = segment name; parts[1..] = fields. Keep name at index 0 so
            // field(n) maps to fields[n] uniformly.
            val fields = parts.mapIndexed { idx, raw ->
                if (idx == 0) HL7Field.of(raw) else HL7Field.parse(raw, d)
            }
            return HL7Segment(name, fields, d)
        }
    }
}
