package org.rite.hl7.validation

import org.rite.hl7.builder.HL7Builder
import org.rite.hl7.model.HL7Message
import org.rite.hl7.util.HL7Date

/**
 * Builds an ACK^R01 response for an inbound message and a [ValidationResult].
 * MSA-1 carries the worst severity (AA/AE/AR); one ERR is emitted per issue.
 * Sender/receiver are swapped from the inbound header so the ACK routes back.
 */
class AckBuilder(private val builder: HL7Builder = HL7Builder.builder().build()) {

    fun build(inbound: HL7Message, result: ValidationResult): HL7Message {
        val h = inbound.header
        val controlId = h?.messageControlId ?: ""
        val errors = result.issues.filter { it.severity != AckSeverity.ACCEPT }

        return builder.ack {
            msh {
                // Swap sender/receiver from the inbound message.
                it.sendingApplication = h?.receivingApplication ?: ""
                it.sendingFacility = h?.receivingFacility ?: ""
                it.receivingApplication = h?.sendingApplication ?: ""
                it.receivingFacility = h?.sendingFacility ?: ""
                it.dateTimeOfMessage = HL7Date.now()
                it.messageControlId = controlId
                it.processingId = h?.processingId ?: "P"
                it.versionId = h?.versionId
            }
            msa {
                it.acknowledgmentCode = result.worst.code
                it.messageControlId = controlId
                it.textMessage = errors.firstOrNull()?.errorText
            }
            errors.forEach { issue ->
                err {
                    it.segmentId = issue.segmentId
                    it.fieldPosition = issue.fieldPosition
                    it.errorCode = issue.errorCode
                    it.errorText = issue.errorText
                    it.severity = issue.severity.code
                }
            }
        }
    }
}
