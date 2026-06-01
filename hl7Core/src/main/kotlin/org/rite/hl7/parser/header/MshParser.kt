package org.rite.hl7.parser.header

import org.rite.hl7.domain.model.MessageHeaderData
import org.rite.hl7.parser.Hl7ParseException

/**
 * Parses an HL7 MSH segment (HL7 v2.1 – v2.8).
 *
 * HL7 MSH rules:
 * - MSH-1 (field separator) is the 4th character in the segment.
 * - MSH-2 (encoding characters) immediately follows the field separator.
 * - Fields are positional and may be missing but must be preserved.
 * - Parser must be version-agnostic and tolerate extra fields.
 */
fun mshParser(msh: String): MessageHeaderData {

    if (!msh.startsWith("MSH") || msh.length < 4) {
        throw Hl7ParseException("Invalid MSH segment", segment = msh)
    }

    /** MSH-1: Field Separator */
    val fieldSeparator = msh[3].toString()

    /** Split fields */
    val fields = msh.split(fieldSeparator)

    /**
     * MSH-2: Encoding Characters
     * Must be exactly 4 characters
     */
    val encodingCharacters = fields
        .getOrNull(1)
        ?.take(4)
        ?.takeIf { it.length == 4 }
        ?: "^~\\&"

    val componentSeparator = encodingCharacters[0].toString()

    /**
     * LENIENT MSH-9 handling
     *
     * If MSH-8 is missing, message type shifts left.
     */
    val rawMessageTypeField =
        when {
            // Correct HL7 position
            fields.getOrNull(8)?.contains(componentSeparator) == true ->
                fields[8]

            // Shifted left (missing MSH-8)
            fields.getOrNull(7)?.contains(componentSeparator) == true ->
                fields[7]

            else -> ""
        }

    val messageTypeComponents =
        rawMessageTypeField.split(componentSeparator)

    return MessageHeaderData(
        fieldSeparator = fieldSeparator,
        encodingCharacters = encodingCharacters,

        sendingApplication = fields.getOrElse(2) { "" },
        sendingFacility = fields.getOrElse(3) { "" },
        receivingApplication = fields.getOrElse(4) { "" },
        receivingFacility = fields.getOrElse(5) { "" },
        messageDateTime = fields.getOrElse(6) { "" },

        messageType = messageTypeComponents.getOrNull(0) ?: "",
        triggerEvent = messageTypeComponents.getOrNull(1) ?: "",

        // Control ID also shifts if MSH-8 missing
        messageControlId =
            if (fields.getOrNull(8)?.contains(componentSeparator) == true)
                fields.getOrElse(9) { "" }
            else
                fields.getOrElse(8) { "" },

        processingId =
            if (fields.getOrNull(8)?.contains(componentSeparator) == true)
                fields.getOrElse(10) { "" }
            else
                fields.getOrElse(9) { "" },

        versionId =
            if (fields.getOrNull(8)?.contains(componentSeparator) == true)
                fields.getOrElse(11) { "" }
            else
                fields.getOrElse(10) { "" },

        countryCode = fields.getOrNull(17)?.takeIf { it.isNotBlank() }
    )
}

