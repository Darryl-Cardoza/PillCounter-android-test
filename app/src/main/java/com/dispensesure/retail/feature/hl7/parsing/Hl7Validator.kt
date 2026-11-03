package com.dispensesure.retail.feature.hl7.parsing

import com.dispensesure.retail.core.region.RegionProvider
import com.dispensesure.retail.core.region.isDin
import com.dispensesure.retail.feature.hl7.parsing.model.OrderGroup
import org.rite.hl7.model.HL7Message
import org.rite.hl7.model.segment.RXESegment
import org.rite.hl7.model.segment.ZDRSegment
import org.rite.hl7.validation.AckSeverity
import org.rite.hl7.validation.HL7Validator
import org.rite.hl7.validation.ValidationConfig
import org.rite.hl7.validation.ValidationIssue
import org.rite.hl7.validation.ValidationResult
import org.rite.hl7.validation.rules.DSValidationRule
import javax.inject.Inject
import javax.inject.Singleton

data class BusinessValidationResult(
    val isValid: Boolean,
    val reason: String? = null,
)

@Singleton
class Hl7Validator @Inject constructor(
    private val regionProvider: RegionProvider,
) {

    private val libValidator: HL7Validator = run {
        val defaultConfig = ValidationConfig()
        val config = defaultConfig.copy(
            knownOrderControlCodes = setOf("NW", "RF", "CA", "HD", "RL", "DC", "XO", "RP"),
        )
        HL7Validator(config)
    }

    // ZDR-specific validator — adds DSValidationRule which checks ZDR field presence and ZDR-03==MSH-10.
    // Kept separate from libValidator so ORC/RXE messages never run DSValidationRule.
    private val zdrLibValidator: HL7Validator = run {
        val config = ValidationConfig().copy(
            knownOrderControlCodes = setOf("NW", "RF", "CA", "HD", "RL", "DC", "XO", "RP"),
            extraRules = listOf(DSValidationRule),
        )
        HL7Validator(config)
    }

    fun validateStructure(message: HL7Message): ValidationResult {
        val libraryResult = libValidator.validate(message)
        // The library only knows NDC formats (and only inspects the first RXE), and its error text carries no RXE index, so it cannot
        // say which RXE a code issue belongs to. Drop its RXE drug-code issues unconditionally and
        // re-validate every RXE's give code ourselves (NDC, plus DIN where the region accepts it), one issue per bad code.
        val rxeSegments = message.segments<RXESegment>(RXESegment.NAME)
        val kept = libraryResult.issues.filterNot { issue -> issue.isLibraryRxeDrugCodeIssue() }
        val acceptsDin = regionProvider.current.hl7OrdersAcceptDin
        val perCodeIssues = rxeSegments.mapNotNull { rxe ->
            val code = rxe.giveCode?.trim().orEmpty()
            // A blank code is the library's "Missing NDC in RXE", which is kept above — adding
            // our own "Invalid NDC" for it would just duplicate the same rejection.
            when {
                code.isEmpty() -> null
                code.isAcceptedDrugCode(acceptsDin) -> null
                else -> invalidRxeCodeIssue(code)
            }
        }
        return ValidationResult(kept + perCodeIssues)
    }

    // NW and RF only — do not call for XO (ZDR-14 may be absent in valid XO messages).
    fun validateZdrStructure(message: HL7Message): ValidationResult =
        zdrLibValidator.validate(message)

    /**
     * Validates a ZDR message with flowType=INVENTORY (INR^U06 native path).
     * Different from dispense: no ZDR-14 rxNumber, no orderAction — only ZDR-01/02/03/05 required.
     * DSValidationRule is not used (it enforces dispense-specific fields); manual checks only.
     */
    fun validateZdrInventoryStructure(message: HL7Message): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()
        val zdr = message.segment<ZDRSegment>(ZDRSegment.NAME)
        if (zdr == null) {
            issues += ValidationIssue(AckSeverity.ERROR, "ZDR segment required for INVENTORY flow", "ZDR", "1", "207")
            return ValidationResult(issues)
        }
        // ZDR-01: flowType must be INVENTORY
        if (zdr.flowType != ZDRSegment.FlowType.INVENTORY) {
            issues += ValidationIssue(AckSeverity.ERROR, "ZDR-01 flowType must be INVENTORY", "ZDR", "1", "207")
        }
        // ZDR-02: layoutVersion required
        if (zdr.layoutVersion.isNullOrBlank()) {
            issues += ValidationIssue(AckSeverity.ERROR, "ZDR-02 layoutVersion is required", "ZDR", "2", "207")
        }
        // ZDR-03: requestMessageId must match MSH-10
        val mshControlId = message.header?.messageControlId.orEmpty()
        val zdrRequestId = zdr.requestMessageId.orEmpty()
        if (zdrRequestId.isBlank()) {
            issues += ValidationIssue(AckSeverity.ERROR, "ZDR-03 requestMessageId is required", "ZDR", "3", "207")
        } else if (zdrRequestId != mshControlId) {
            issues += ValidationIssue(AckSeverity.ERROR, "ZDR-03 requestMessageId must match MSH-10 ($mshControlId)", "ZDR", "3", "207")
        }
        // ZDR-05: at least one drug with a non-blank NDC required
        if (zdr.drugs.none { it.ndc.isNotBlank() }) {
            issues += ValidationIssue(AckSeverity.ERROR, "ZDR-05 at least one drug/NDC is required", "ZDR", "5", "207")
        }
        return ValidationResult(issues)
    }

    fun validateBusinessRules(order: OrderGroup): BusinessValidationResult {
        // Library validate() already checks NDC format, quantity range, required segments.
        // Only add rules the library cannot know — app-domain checks.
        if (order.orderControl == "RF") {
            val pending = order.pendingRefills
            if (pending != null && pending <= 0) {
                return BusinessValidationResult(
                    isValid = false,
                    reason = "No refills remaining for ${order.placerOrderNumber} (RXE-16=$pending)",
                )
            }
        }
        return BusinessValidationResult(isValid = true)
    }
}

// The library raises an "Invalid NDC in RXE" issue (segmentId=RXE, fieldPosition=2) for RXE-2 that
// fails NDC validation; it cannot be attributed to a specific RXE, so it is replaced by our own.
private fun ValidationIssue.isLibraryRxeDrugCodeIssue(): Boolean =
    segmentId == "RXE" && fieldPosition == "2" && errorText.contains("Invalid NDC", ignoreCase = true)

private fun invalidRxeCodeIssue(code: String) =
    ValidationIssue(AckSeverity.ERROR, "Invalid NDC in RXE: '$code'", "RXE", "2", "207")

// Same shapes the HL7 library accepts for an NDC: 10-11 digits undashed, or dashed 4-5 / 3-4 / 1-2.
private val NDC_PATTERN = Regex("""^(\d{10,11}|\d{4,5}-\d{3,4}-\d{1,2})$""")

private fun String.isAcceptedDrugCode(acceptsDin: Boolean): Boolean =
    NDC_PATTERN.matches(this) || (acceptsDin && isDin())
