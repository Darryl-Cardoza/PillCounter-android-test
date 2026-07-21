package org.rite.hl7.model.segment

import org.rite.hl7.model.SegmentDefinition
import org.rite.hl7.model.TypedSegment
import org.rite.hl7.model.ast.HL7Segment

/** MSH — Message Header. */
class MSHSegment(raw: HL7Segment) : TypedSegment(raw) {
    val fieldSeparator: String get() = fieldValue(1)
    val encodingCharacters: String get() = fieldValue(2)
    val sendingApplication: String get() = fieldValue(3)
    val sendingFacility: String get() = fieldValue(4)
    val receivingApplication: String get() = fieldValue(5)
    val receivingFacility: String get() = fieldValue(6)
    val dateTimeOfMessage: String get() = fieldValue(7)
    val security: String get() = fieldValue(8)
    val messageType: String get() = fieldValue(9)            // full MSH-9, e.g. "RDS^O13"
    val messageCode: String get() = component(9, 1)          // MSH-9.1, e.g. "RDS"
    val triggerEvent: String get() = component(9, 2)         // MSH-9.2, e.g. "O13"
    val messageControlId: String get() = fieldValue(10)
    val processingId: String get() = fieldValue(11)
    val versionId: String get() = fieldValue(12)
    val sequenceNumber: String get() = fieldValue(13)
    val countryCode: String get() = fieldValue(17)

    companion object {
        const val NAME = "MSH"
        val Definition = SegmentDefinition(NAME) { MSHSegment(it) }
    }
}

/** PID — Patient Identification. */
class PIDSegment(raw: HL7Segment) : TypedSegment(raw) {
    val setId: String get() = fieldValue(1)
    val patientId: String get() = component(3, 1)
    val patientIdAssigningAuthority: String get() = component(3, 4)
    val patientIdType: String get() = component(3, 5)
    val familyName: String get() = component(5, 1)
    val givenName: String get() = component(5, 2)
    val middleName: String get() = component(5, 3)
    val dateOfBirth: String get() = fieldValue(7)
    val sex: String get() = fieldValue(8)
    val streetAddress: String get() = component(11, 1)
    val city: String get() = component(11, 3)
    val state: String get() = component(11, 4)
    val zipCode: String get() = component(11, 5)
    val country: String get() = component(11, 6)

    companion object {
        const val NAME = "PID"
        val Definition = SegmentDefinition(NAME) { PIDSegment(it) }
    }
}

/** PV1 — Patient Visit. */
class PV1Segment(raw: HL7Segment) : TypedSegment(raw) {
    val setId: String get() = fieldValue(1)
    val patientClass: String get() = fieldValue(2)
    val pointOfCare: String get() = component(3, 1)
    val room: String get() = component(3, 2)
    val bed: String get() = component(3, 3)
    val facility: String get() = component(3, 4)
    val attendingDoctorId: String get() = component(7, 1)
    val attendingDoctorFamilyName: String get() = component(7, 2)
    val attendingDoctorGivenName: String get() = component(7, 3)
    val visitNumber: String get() = component(19, 1)
    val admitDateTime: String get() = fieldValue(44)

    companion object {
        const val NAME = "PV1"
        val Definition = SegmentDefinition(NAME) { PV1Segment(it) }
    }
}

/** ORC — Common Order. */
class ORCSegment(raw: HL7Segment) : TypedSegment(raw) {
    val orderControl: String get() = fieldValue(1)
    val placerOrderNumber: String get() = component(2, 1)
    val placerOrderNamespace: String get() = component(2, 2)
    val fillerOrderNumber: String get() = component(3, 1)
    val fillerOrderNamespace: String get() = component(3, 2)
    val orderStatus: String get() = fieldValue(5)
    val dateTimeOfTransaction: String get() = fieldValue(9)
    val orderingProviderId: String get() = component(12, 1)
    val orderingProviderFamilyName: String get() = component(12, 2)
    val orderingProviderGivenName: String get() = component(12, 3)
    val orderingFacility: String get() = fieldValue(21)

    companion object {
        const val NAME = "ORC"
        val Definition = SegmentDefinition(NAME) { ORCSegment(it) }
    }
}

/** MSA — Message Acknowledgement. */
class MSASegment(raw: HL7Segment) : TypedSegment(raw) {
    val acknowledgmentCode: String get() = fieldValue(1)   // AA / AE / AR
    val messageControlId: String get() = fieldValue(2)
    val textMessage: String get() = fieldValue(3)

    companion object {
        const val NAME = "MSA"
        val Definition = SegmentDefinition(NAME) { MSASegment(it) }
    }
}

/** ERR — Error. */
class ERRSegment(raw: HL7Segment) : TypedSegment(raw) {
    val segmentId: String get() = component(2, 1)
    val sequence: String get() = component(2, 2)
    val fieldPosition: String get() = component(2, 3)
    val errorCode: String get() = component(3, 1)
    val errorText: String get() = component(3, 2)
    val severity: String get() = fieldValue(4)
    val applicationErrorCode: String get() = component(5, 1)
    val applicationErrorText: String get() = component(5, 2)
    val diagnosticInformation: String get() = fieldValue(7)
    val userMessage: String get() = fieldValue(8)

    companion object {
        const val NAME = "ERR"
        val Definition = SegmentDefinition(NAME) { ERRSegment(it) }
    }
}

/** NTE — Notes and Comments. */
class NTESegment(raw: HL7Segment) : TypedSegment(raw) {
    val setId: String get() = fieldValue(1)
    val sourceOfComment: String get() = fieldValue(2)
    val comment: String get() = fieldValue(3)
    val commentType: String get() = fieldValue(4)

    companion object {
        const val NAME = "NTE"
        val Definition = SegmentDefinition(NAME) { NTESegment(it) }
    }
}
