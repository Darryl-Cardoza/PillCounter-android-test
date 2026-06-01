package org.rite.hl7.builder.header

import org.rite.hl7.builder.header.MshVersionCapabilities
import org.rite.hl7.domain.model.MessageHeaderData
import org.rite.hl7.domain.utils.HL7Utils
import org.rite.hl7.util.currentLocalDateTime

/**
 * Serializes a MessageHeaderData object into an HL7 MSH segment.
 *
 * HL7 MSH rules:
 * - MSH-1 (Field Separator) is implicit and NOT serialized as a field.
 * - Fields are positional and must include empty placeholders.
 * - Maximum allowed fields depend on HL7 version (2.1 → 2.8).
 */
fun buildMSH(header: MessageHeaderData): String {

    /**
     * MSH-9: Message Type (composite)
     * Format: <MessageType>^<TriggerEvent>
     * Examples:
     *   RDE^O11
     *   RDS^O13
     */
    val messageTypeComposite = HL7Utils.buildComponent(
        header.messageType,
        header.triggerEvent
    )

    val allFields = listOf(

        /** Segment name (not an HL7 field number) */
        "MSH",

        /** MSH-2: Encoding Characters (component, repetition, escape, subcomponent) */
        header.encodingCharacters,

        /** MSH-3: Sending Application */
        header.sendingApplication,

        /** MSH-4: Sending Facility */
        header.sendingFacility,

        /** MSH-5: Receiving Application */
        header.receivingApplication,

        /** MSH-6: Receiving Facility */
        header.receivingFacility,

        /** MSH-7: Date/Time of Message (TS) */
        header.messageDateTime,

        /** MSH-8: Security (optional, rarely used) */
        "",

        /** MSH-9: Message Type (composite: MessageType^TriggerEvent) */
        messageTypeComposite,

        /** MSH-10: Message Control ID (auto-generated if blank) */
        header.messageControlId.ifBlank {
            currentLocalDateTime()
        },

        /** MSH-11: Processing ID (P = Production, T = Test) */
        header.processingId,

        /** MSH-12: Version ID (HL7 v2.1 – v2.8) */
        header.versionId,

        /** MSH-13: Sequence Number (optional) */
        "",

        /** MSH-14: Continuation Pointer (optional) */
        "",

        /** MSH-15: Accept Acknowledgment Type (optional) */
        "",

        /** MSH-16: Application Acknowledgment Type (optional) */
        "",

        /** MSH-17: Country Code (introduced in HL7 v2.5) */
        header.countryCode ?: "",

        /** MSH-18: Character Set */
        "",

        /** MSH-19: Principal Language of Message */
        "",

        /** MSH-20: Alternate Character Set Handling Scheme */
        "",

        /** MSH-21: Message Profile Identifier */
        ""
    )


    /**
     * Determine the maximum allowed MSH field number
     * for the given HL7 version (2.1 → 2.8).
     */
    val maxFieldIndex = MshVersionCapabilities.maxField(header.versionId)

    /**
     * Serialize only the allowed fields and join them
     * using the configured field separator.
     */
    return allFields
        .take(maxFieldIndex + 1)
        .joinToString(header.fieldSeparator)
}
