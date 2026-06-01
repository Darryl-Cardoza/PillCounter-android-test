package org.rite.hl7.domain.model


/**
 * Parsed representation of the HL7 OBX (Observation Result) segment.
 */
data class ObservationData(

    /** OBX-1: Set ID */
    val setId: String,

    /** OBX-2: Value Type (e.g., RP, ST, NM) */
    val valueType: String,

    /** OBX-3.1: Observation Identifier Code */
    val observationId: String,

    /** OBX-3.2: Observation Identifier Text */
    val observationText: String? = null,

    /** OBX-3.3: Coding System */
    val codingSystem: String? = null,

    /** OBX-5: Observation Value (URL, URI, text, number, etc.) */
    val observationValue: String,

    /** OBX-11: Result Status (F, P, C, etc.) */
    val resultStatus: String
)
