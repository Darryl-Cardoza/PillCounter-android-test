package org.rite.hl7.parser.patient

import org.rite.hl7.domain.model.PatientData

/**
 * Parses the PID segment and extracts patient identity and demographic details.
 * Returns null if the PID segment is not present in the message.
 */
fun patientParser(
    segments: Map<String, List<List<String>>>,
    compSep: String
): PatientData? {

    /** Retrieve the first PID segment; patient is optional in HL7 **/
    val pid = segments["PID"]?.firstOrNull() ?: return null

    /** Parsed patient identifier components from PID-3 **/
    val pidParts = pid.getOrElse(3) { "" }.split(compSep)

    /** Parsed patient name components from PID-5 **/
    val nameParts = pid.getOrNull(5)?.split(compSep) ?: emptyList()

    /** Parsed patient address components from PID-11 **/
    val addressParts = pid.getOrNull(11)?.split(compSep) ?: emptyList()

    /** Build and return parsed patient data **/
    return PatientData(

        /** Primary patient identifier (MRN) from PID-3.1 **/
        patientId = pidParts.getOrNull(0) ?: "",

        /** Assigning authority for patient identifier from PID-3.4 **/
        patientIdAssigningAuthority =
            pidParts.getOrNull(3)?.takeIf { it.isNotBlank() },

        /** Patient identifier type (MR, SS, etc.) from PID-3.5 **/
        patientIdType =
            pidParts.getOrNull(4)?.takeIf { it.isNotBlank() },

        /** Patient family (last) name from PID-5.1 **/
        familyName =
            nameParts.getOrNull(0)?.takeIf { it.isNotBlank() },

        /** Patient given (first) name from PID-5.2 **/
        givenName =
            nameParts.getOrNull(1)?.takeIf { it.isNotBlank() },

        /** Patient middle name or initial from PID-5.3 **/
        middleName =
            nameParts.getOrNull(2)?.takeIf { it.isNotBlank() },

        /** Patient date of birth (YYYYMMDD) from PID-7 **/
        dateOfBirth =
            pid.getOrNull(7)?.takeIf { it.isNotBlank() },

        /** Patient administrative sex from PID-8 **/
        sex =
            pid.getOrNull(8)?.takeIf { it.isNotBlank() },

        /** Patient street address from PID-11.1 **/
        streetAddress =
            addressParts.getOrNull(0)?.takeIf { it.isNotBlank() },

        /** Patient city from PID-11.3 **/
        city =
            addressParts.getOrNull(2)?.takeIf { it.isNotBlank() },

        /** Patient state or province from PID-11.4 **/
        state =
            addressParts.getOrNull(3)?.takeIf { it.isNotBlank() },

        /** Patient postal or ZIP code from PID-11.5 **/
        zipCode =
            addressParts.getOrNull(4)?.takeIf { it.isNotBlank() },

        /** Patient country from PID-11.6 **/
        country =
            addressParts.getOrNull(5)?.takeIf { it.isNotBlank() }
    )
}
