package org.rite.hl7.encoding

/**
 * The five HL7 v2.x encoding characters.
 *
 * The field separator is taken from the 4th character of the MSH segment
 * (the character immediately after "MSH"). The remaining four are read from
 * MSH-2 ("encoding characters"), in order: component, repetition, escape,
 * subcomponent.
 *
 * Per the HL7 spec, delimiters must always be read from the message itself —
 * never assumed — because a sender may use non-standard characters.
 */
data class HL7Delimiters(
    val field: Char = '|',
    val component: Char = '^',
    val repetition: Char = '~',
    val escape: Char = '\\',
    val subcomponent: Char = '&',
) {
    /** The MSH-2 string ("^~\&" by default) — the four encoding chars in order. */
    val encodingCharacters: String
        get() = "$component$repetition$escape$subcomponent"

    companion object {
        /** Standard HL7 delimiters: `|^~\&`. */
        val DEFAULT = HL7Delimiters()

        /**
         * Derives delimiters from a raw MSH segment line.
         *
         * Layout: `MSH` + <field-sep> + <encoding-chars> + <field-sep> + ...
         * e.g. `MSH|^~\&|...` → field='|', component='^', repetition='~',
         * escape='\', subcomponent='&'.
         *
         * Falls back to [DEFAULT] for any character that cannot be read, so a
         * truncated header still yields a usable delimiter set.
         */
        fun fromMshLine(mshLine: String): HL7Delimiters {
            if (mshLine.length < 4 || !mshLine.startsWith("MSH")) return DEFAULT

            val field = mshLine[3]
            // MSH-2 is the run of characters after the field separator, up to the
            // next field separator. Standard length is 4 but we read defensively.
            val enc = mshLine.drop(4).takeWhile { it != field }

            return HL7Delimiters(
                field = field,
                component = enc.getOrNull(0) ?: DEFAULT.component,
                repetition = enc.getOrNull(1) ?: DEFAULT.repetition,
                escape = enc.getOrNull(2) ?: DEFAULT.escape,
                subcomponent = enc.getOrNull(3) ?: DEFAULT.subcomponent,
            )
        }
    }
}
