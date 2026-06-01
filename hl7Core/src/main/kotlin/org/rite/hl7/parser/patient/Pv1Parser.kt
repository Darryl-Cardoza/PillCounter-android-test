package org.rite.hl7.parser.patient

import org.rite.hl7.domain.model.VisitData

/**
 * Parses the PV1 segment and extracts patient visit and encounter details.
 * PV1 defines where the patient is located and who is responsible for care.
 */
fun parseVisit(
    segments: Map<String, List<List<String>>>,
    compSep: String
): VisitData? {

    /** Retrieve the first PV1 segment; visit information is optional **/
    val pv1 = segments["PV1"]?.firstOrNull() ?: return null

    /** Parsed patient location components from PV1-3 **/
    val locParts = pv1.getOrNull(3)?.split(compSep) ?: emptyList()

    /** Parsed attending provider components from PV1-7 **/
    val doctorParts = pv1.getOrNull(7)?.split(compSep) ?: emptyList()

    /** Parsed visit identifier components from PV1-19 **/
    val visitParts = pv1.getOrNull(19)?.split(compSep) ?: emptyList()

    /** Build and return parsed visit data **/
    return VisitData(
        visitNumber = visitParts.getOrNull(0)?.takeIf { it.isNotBlank() },
        visitNumberAssigningAuthority = visitParts.getOrNull(3)?.takeIf { it.isNotBlank() },
        patientClass = pv1.getOrNull(2)?.takeIf { it.isNotBlank() },
        locPointOfCare = locParts.getOrNull(0)?.takeIf { it.isNotBlank() },
        locRoom = locParts.getOrNull(1)?.takeIf { it.isNotBlank() },
        locBed = locParts.getOrNull(2)?.takeIf { it.isNotBlank() },
        locFacility = locParts.getOrNull(3)?.takeIf { it.isNotBlank() },
        attendingDoctorId = doctorParts.getOrNull(0)?.takeIf { it.isNotBlank() },
        attendingDoctorFamilyName = doctorParts.getOrNull(1)?.takeIf { it.isNotBlank() },
        attendingDoctorGivenName = doctorParts.getOrNull(2)?.takeIf { it.isNotBlank() },
        admitDateTime = pv1.getOrNull(44)?.takeIf { it.isNotBlank() }
    )
}
