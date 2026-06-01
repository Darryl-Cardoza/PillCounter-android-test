package org.rite.hl7.domain.model


// ==================== MESSAGE HEADER (MSH) ====================
// Parsed representation of the HL7 MSH segment.
// Defines message identity, routing, version, and encoding rules.
// MSH controls how all other segments are parsed and built.

data class MessageHeaderData(

    /** Parsed field separator used in this HL7 message (MSH-1) **/
    val fieldSeparator: String,

    /** Parsed encoding characters defining HL7 delimiters (MSH-2) **/
    val encodingCharacters: String,

    /** Parsed sending application identifier (MSH-3) **/
    val sendingApplication: String,

    /** Parsed sending facility identifier (MSH-4) **/
    val sendingFacility: String,

    /** Parsed receiving application identifier (MSH-5) **/
    val receivingApplication: String,

    /** Parsed receiving facility identifier (MSH-6) **/
    val receivingFacility: String,

    /** Parsed message creation date and time (MSH-7) **/
    val messageDateTime: String,

    /** Parsed HL7 message type (e.g., ADT, RDS, ORM) (MSH-9.1) **/
    val messageType: String,

    /** Parsed HL7 trigger event (e.g., A01, O13) (MSH-9.2) **/
    val triggerEvent: String,

    /** Parsed unique message control ID for ACK and idempotency (MSH-10) **/
    val messageControlId: String,

    /** Parsed processing mode indicator (P=Production, T=Test) (MSH-11) **/
    val processingId: String,

    /** Parsed HL7 version identifier (e.g., 2.3, 2.5.1) (MSH-12) **/
    val versionId: String,

    /** Parsed optional country code if present (MSH-17) **/
    val countryCode: String? = null
)
