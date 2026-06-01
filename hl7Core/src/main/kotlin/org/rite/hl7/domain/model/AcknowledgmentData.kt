package org.rite.hl7.domain.model

// ==================== ACKNOWLEDGMENT (MSA) ====================
// Parsed representation of the HL7 MSA segment.
// Indicates whether a message was accepted, rejected, or accepted with errors.

data class AcknowledgmentData(

    /** Parsed acknowledgment code (AA=Accept, AE=Error, AR=Reject) (MSA-1) **/
    val acknowledgmentCode: String,

    /** Parsed message control ID correlating to original MSH-10 (MSA-2) **/
    val messageControlId: String,

    /** Parsed human-readable acknowledgment text (MSA-3) **/
    val textMessage: String? = null,

    /** Parsed error condition derived from ERR segment (if present) **/
    val errorCondition: String? = null
)
