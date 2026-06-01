package org.rite.hl7.domain.model


// ==================== PATIENT (PID) ====================
// Parsed representation of the HL7 PID segment.
// Contains patient identity, demographics, and address information.

data class PatientData(

    /** Parsed primary patient identifier (MRN) (PID-3.1) **/
    val patientId: String,

    /** Parsed assigning authority for patient identifier (PID-3.4) **/
    val patientIdAssigningAuthority: String? = null,

    /** Parsed identifier type code (MR, SS, etc.) (PID-3.5) **/
    val patientIdType: String? = null,

    /** Parsed patient family/last name (PID-5.1) **/
    val familyName: String? = null,

    /** Parsed patient given/first name (PID-5.2) **/
    val givenName: String? = null,

    /** Parsed patient middle name or initial (PID-5.3) **/
    val middleName: String? = null,

    /** Parsed patient date of birth (YYYYMMDD) (PID-7) **/
    val dateOfBirth: String? = null,

    /** Parsed administrative sex (PID-8) **/
    val sex: String? = null,

    /** Parsed patient street address (PID-11.1) **/
    val streetAddress: String? = null,

    /** Parsed patient city (PID-11.3) **/
    val city: String? = null,

    /** Parsed patient state or province (PID-11.4) **/
    val state: String? = null,

    /** Parsed patient postal or ZIP code (PID-11.5) **/
    val zipCode: String? = null,

    /** Parsed patient country (PID-11.6) **/
    val country: String? = null
)
