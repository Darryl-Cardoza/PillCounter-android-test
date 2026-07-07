package org.rite.hl7.validation

import org.rite.hl7.model.HL7Message
import org.rite.hl7.model.segment.QPDSegment
import org.rite.hl7.model.segment.ZADSegment

/**
 * Validates a parsed [HL7Message] against required-field, message-shape, and
 * spec extension rules (§11/§12/§13), producing a [ValidationResult] that drives
 * the ACK code.
 *
 * Rules follow the project-authoritative Z-segment layout:
 *  - QBP^Q11: QPD-1 must equal the configured query name → else AR.
 *  - ZAD: adjustmentType and adjustmentReason must be recognized → else AR;
 *    a reason that requires a comment must have one → else AE.
 *
 * Construct with a [ValidationConfig] to override the site defaults.
 */
class HL7Validator(private val config: ValidationConfig = ValidationConfig.DEFAULT) {

    fun validate(message: HL7Message): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()

        validateHeader(message, issues)
        validateQuery(message, issues)
        validateAdjustments(message, issues)

        return ValidationResult(issues)
    }

    private fun validateHeader(message: HL7Message, issues: MutableList<ValidationIssue>) {
        val header = message.header
        if (header == null) {
            issues += ValidationIssue(AckSeverity.REJECT, "Missing MSH segment", "MSH", "0", "100")
            return
        }
        if (header.messageControlId.isBlank()) {
            issues += ValidationIssue(AckSeverity.ERROR, "Missing message control ID (MSH-10)", "MSH", "10", "101")
        }
    }

    private fun validateQuery(message: HL7Message, issues: MutableList<ValidationIssue>) {
        if (message.messageCode != "QBP") return
        val qpd = message.segment<QPDSegment>(QPDSegment.NAME) ?: run {
            issues += ValidationIssue(AckSeverity.REJECT, "Missing QPD segment", "QPD", "0", "200")
            return
        }
        if (qpd.queryNameCode != config.expectedQueryName) {
            issues += ValidationIssue(
                AckSeverity.REJECT,
                "Unsupported query",
                "QPD", "1", "200",
            )
        }
    }

    private fun validateAdjustments(message: HL7Message, issues: MutableList<ValidationIssue>) {
        for (zad in message.segments<ZADSegment>(ZADSegment.NAME)) {
            if (zad.adjustmentType.isNotBlank() &&
                zad.adjustmentType !in config.knownAdjustmentTypes
            ) {
                issues += ValidationIssue(
                    AckSeverity.REJECT,
                    "Invalid adjustment type: ${zad.adjustmentType}",
                    "ZAD", "2", "200",
                )
            }
            val reason = zad.adjustmentReason
            if (reason.isNotBlank() && reason !in config.knownAdjustmentReasons) {
                issues += ValidationIssue(
                    AckSeverity.REJECT,
                    "Unknown adjustment reason code: $reason",
                    "ZAD", "4", "200",
                )
            }
            if (reason in config.commentRequiredReasons && zad.approvedBy.isBlank()) {
                issues += ValidationIssue(
                    AckSeverity.ERROR,
                    "Adjustment comment required for reason $reason",
                    "ZAD", "6", "206",
                )
            }
        }
    }

}
