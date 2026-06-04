package org.rite.hl7.builder.patient

import org.rite.hl7.domain.model.VisitData
import org.rite.hl7.domain.utils.HL7Utils
import org.rite.hl7.domain.utils.HL7Utils.buildComponent

fun buildPV1(visit: VisitData): String {

    // PV1-3: Assigned Patient Location (POC ^ Room ^ Bed ^ Facility)
    val location = buildComponent(
        visit.locPointOfCare ?: "",
        visit.locRoom ?: "",
        visit.locBed ?: "",
        visit.locFacility ?: ""
    )

    // PV1-7: Attending Doctor (ID ^ family ^ given)
    val attendingDoctor = buildComponent(
        visit.attendingDoctorId ?: "",
        visit.attendingDoctorFamilyName ?: "",
        visit.attendingDoctorGivenName ?: ""
    )

    // PV1-19: Visit Number (ID ^ ^ ^ assigning authority)
    val visitNumber = buildComponent(
        visit.visitNumber ?: "",
        "", // Check digit
        "", // Check digit scheme
        visit.visitNumberAssigningAuthority ?: ""
    )

    /** Assemble PV1 segment with HL7-defined field positions **/
    return HL7Utils.buildSegment(
        "PV1",  /** Segment ID **/

        /** PV1-1: Set ID **/
        "1",

        /** PV1-2: Patient class **/
        visit.patientClass ?: "",

        /** PV1-3: Assigned patient location **/
        location,

        /** PV1-4: Admission type (not used) **/
        "",

        /** PV1-5: Preadmit number (not used) **/
        "",

        /** PV1-6: Prior patient location (not used) **/
        "",

        /** PV1-7: Attending doctor **/
        attendingDoctor,

        /** PV1-8: Referring doctor (not used) **/
        "",

        /** PV1-9: Consulting doctor (not used) **/
        "",

        /** PV1-10: Hospital service (not used) **/
        "",

        /** PV1-11: Temporary location (not used) **/
        "",

        /** PV1-12: Preadmit test indicator (not used) **/
        "",

        /** PV1-13: Re-admission indicator (not used) **/
        "",

        /** PV1-14: Admit source (not used) **/
        "",

        /** PV1-15: Ambulatory status (not used) **/
        "",

        /** PV1-16: VIP indicator (not used) **/
        "",

        /** PV1-17: Admitting doctor (not used) **/
        "",

        /** PV1-18: Patient type (not used) **/
        "",

        /** PV1-19: Visit number **/
        visitNumber,

        /** PV1-20: Financial class (not used) **/
        "",

        /** PV1-21: Charge price indicator (not used) **/
        "",

        /** PV1-22: Courtesy code (not used) **/
        "",

        /** PV1-23: Credit rating (not used) **/
        "",

        /** PV1-24: Contract code (not used) **/
        "",

        /** PV1-25: Contract effective date (not used) **/
        "",

        /** PV1-26: Contract amount (not used) **/
        "",

        /** PV1-27: Contract period (not used) **/
        "",

        /** PV1-28: Interest code (not used) **/
        "",

        /** PV1-29: Transfer to bad debt code (not used) **/
        "",

        /** PV1-30: Transfer to bad debt date (not used) **/
        "",

        /** PV1-31: Bad debt agency code (not used) **/
        "",

        /** PV1-32: Bad debt transfer amount (not used) **/
        "",

        /** PV1-33: Bad debt recovery amount (not used) **/
        "",

        /** PV1-34: Delete account indicator (not used) **/
        "",

        /** PV1-35: Delete account date (not used) **/
        "",

        /** PV1-36: Discharge disposition (not used) **/
        "",

        /** PV1-37: Discharged to location (not used) **/
        "",

        /** PV1-38: Diet type (not used) **/
        "",

        /** PV1-39: Servicing facility (not used) **/
        "",

        /** PV1-40: Bed status (not used) **/
        "",

        /** PV1-41: Account status (not used) **/
        "",

        /** PV1-42: Pending location (not used) **/
        "",

        /** PV1-43: Prior temporary location (not used) **/
        "",

        /** PV1-44: Admit date/time **/
        visit.admitDateTime ?: ""
    )
}
