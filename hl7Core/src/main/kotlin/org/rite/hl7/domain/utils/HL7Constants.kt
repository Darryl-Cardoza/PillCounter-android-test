package org.rite.hl7.hl7.domain.utils

object HL7Constants {

    // Separates fields within an HL7 segment
    const val FIELD_SEPARATOR = "|"

    // Separates components inside a composite field
    const val COMPONENT_SEPARATOR = "^"

    // Separates repeating field values
    const val REPETITION_SEPARATOR = "~"

    // Introduces HL7 escape sequences
    const val ESCAPE_CHARACTER = "\\"

    // Separates subcomponents within a component
    const val SUBCOMPONENT_SEPARATOR = "&"

    // Marks the end of an HL7 segment
    const val SEGMENT_TERMINATOR = "\r"

    // Standard HL7 encoding characters (MSH-2)
    const val ENCODING_CHARACTERS = "^~\\&"
}
