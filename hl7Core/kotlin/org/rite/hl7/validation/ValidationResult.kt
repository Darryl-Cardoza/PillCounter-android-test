package org.rite.hl7.validation

/** ACK severity for a validation issue, mapped to MSA-1 codes. */
enum class AckSeverity(val code: String) {
    ACCEPT("AA"),
    ERROR("AE"),
    REJECT("AR");
}

/** A single validation problem, carrying enough to build an ERR segment. */
data class ValidationIssue(
    val severity: AckSeverity,
    val errorText: String,
    val segmentId: String? = null,
    val fieldPosition: String? = null,
    val errorCode: String? = null,
)

/** Aggregated validation outcome. [worst] decides the ACK code. */
class ValidationResult(val issues: List<ValidationIssue>) {
    val isValid: Boolean get() = issues.none { it.severity != AckSeverity.ACCEPT }

    /** Highest severity present (REJECT > ERROR > ACCEPT). */
    val worst: AckSeverity
        get() = when {
            issues.any { it.severity == AckSeverity.REJECT } -> AckSeverity.REJECT
            issues.any { it.severity == AckSeverity.ERROR } -> AckSeverity.ERROR
            else -> AckSeverity.ACCEPT
        }

    companion object {
        val VALID = ValidationResult(emptyList())
    }
}
