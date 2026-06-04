package org.rite.hl7.domain.model


// ==================== ERROR (ERR) ====================
// Parsed representation of the HL7 ERR segment.
// Captures detailed error, warning, or informational feedback.

data class ErrorData(

    /** Parsed segment ID where the error occurred (ERR-2.1) **/
    val segmentId: String? = null,

    /** Parsed repetition or sequence number of the segment (ERR-2.2) **/
    val sequence: String? = null,

    /** Parsed field position that caused the error (ERR-2.3) **/
    val fieldPosition: String? = null,

    /** Parsed HL7-defined error code (ERR-3.1) **/
    val errorCode: String? = null,

    /** Parsed description of the HL7 error (ERR-3.2) **/
    val errorDescription: String? = null,

    /** Parsed severity of the error (E=Error, W=Warning, I=Info) (ERR-4) **/
    val severity: String? = null,

    /** Parsed application-specific error code (ERR-5.1) **/
    val applicationErrorCode: String? = null,

    /** Parsed application-specific error description (ERR-5.2) **/
    val applicationErrorText: String? = null,

    /** Parsed diagnostic or system-level information (ERR-7) **/
    val diagnosticInfo: String? = null,

    /** Parsed user-friendly error message (ERR-8) **/
    val userMessage: String? = null
)
