package org.rite.hl7.domain.model


// ==================== VISIT (PV1) ====================
// Parsed representation of the HL7 PV1 segment.
// Contains encounter, location, and attending provider information.

data class VisitData(

    /** Parsed visit/encounter number (PV1-19.1) **/
    val visitNumber: String? = null,

    /** Parsed assigning authority for visit number (PV1-19.4) **/
    val visitNumberAssigningAuthority: String? = null,

    /** Parsed patient class (Inpatient, Outpatient, etc.) (PV1-2) **/
    val patientClass: String? = null,

    /** Parsed point of care or ward/unit (PV1-3.1) **/
    val locPointOfCare: String? = null,

    /** Parsed room identifier within the care location (PV1-3.2) **/
    val locRoom: String? = null,

    /** Parsed bed identifier within the room (PV1-3.3) **/
    val locBed: String? = null,

    /** Parsed facility identifier for patient location (PV1-3.4) **/
    val locFacility: String? = null,

    /** Parsed attending doctor identifier (PV1-7.1) **/
    val attendingDoctorId: String? = null,

    /** Parsed attending doctor family name (PV1-7.2) **/
    val attendingDoctorFamilyName: String? = null,

    /** Parsed attending doctor given name (PV1-7.3) **/
    val attendingDoctorGivenName: String? = null,

    /** Parsed patient admission date and time (PV1-44) **/
    val admitDateTime: String? = null
)
