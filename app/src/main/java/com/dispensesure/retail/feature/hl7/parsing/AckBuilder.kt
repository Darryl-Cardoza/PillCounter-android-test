package com.dispensesure.retail.feature.hl7.parsing

import com.dispensesure.retail.core.utils.logger.AppLogger
import org.rite.hl7.HL7
import org.rite.hl7.model.HL7Message
import org.rite.hl7.validation.AckSeverity
import org.rite.hl7.validation.ValidationIssue
import org.rite.hl7.validation.ValidationResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AckBuilder @Inject constructor(
    private val hl7: HL7,
) {

    private val logger = AppLogger("AckBuilder")

    fun buildAck(message: HL7Message): String = hl7.ack(message)

    fun buildError(message: HL7Message, reason: String): String {
        logger.w("Sending AE for ${message.header?.messageControlId.orEmpty()}: $reason")
        val issue = ValidationIssue(
            severity = AckSeverity.ERROR,
            errorText = reason,
            segmentId = "",
            fieldPosition = "",
            errorCode = "",
        )
        return buildErrorFromResult(message, ValidationResult(listOf(issue)))
    }

    fun buildErrorFromResult(message: HL7Message, result: ValidationResult): String {
        val code = when (result.worst) {
            AckSeverity.REJECT -> "AR"
            else -> "AE"
        }
        val reason = result.issues.firstOrNull()?.errorText ?: "Validation failed"
        logger.w("Sending $code for ${message.header?.messageControlId.orEmpty()}: $reason")
        return org.rite.hl7.validation.AckBuilder().build(message, result).encode()
    }
}
