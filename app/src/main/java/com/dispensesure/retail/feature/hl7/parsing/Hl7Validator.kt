package com.dispensesure.retail.feature.hl7.parsing

import com.dispensesure.retail.core.region.RegionProvider
import com.dispensesure.retail.core.region.isDin
import com.dispensesure.retail.feature.hl7.parsing.model.OrderGroup
import org.rite.hl7.model.HL7Message
import org.rite.hl7.model.segment.RXESegment
import org.rite.hl7.validation.AckSeverity
import org.rite.hl7.validation.HL7Validator
import org.rite.hl7.validation.ValidationConfig
import org.rite.hl7.validation.ValidationIssue
import org.rite.hl7.validation.ValidationResult
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

    fun validateStructure(message: HL7Message): ValidationResult {
        val libraryResult = libValidator.validate(message)
        // The library only knows NDC formats, so it rejects a DIN the region accepts. It does
        // identify which RXE a code issue belongs to — "Invalid NDC in RXE (order 1)", "(order 2)"
        // for several RXEs, plain "Invalid NDC in RXE" for one — but every one of those issues is
        // wrong wherever a DIN is valid. Drop its RXE drug-code issues unconditionally and
        // re-validate every RXE's give code ourselves (NDC, plus DIN where the region accepts
        // it), one issue per bad code.
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

    // Inbound ZDR messages (dispense and inventory alike) are validated by NativeZdrValidator,
    // which runs before any handler and owns every native reject reason.

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

// The library raises an "Invalid NDC in RXE" issue (segmentId=RXE, fieldPosition=2) for an RXE-2
// that fails NDC validation — suffixed "(order n)" when the message carries several RXEs. Matched
// on the stem so both forms are caught, and replaced by our own region-aware check.
private fun ValidationIssue.isLibraryRxeDrugCodeIssue(): Boolean =
    segmentId == "RXE" && fieldPosition == "2" && errorText.contains("Invalid NDC", ignoreCase = true)

private fun invalidRxeCodeIssue(code: String) =
    ValidationIssue(AckSeverity.ERROR, "Invalid NDC in RXE: '$code'", "RXE", "2", "207")

// Same shapes the HL7 library accepts for an NDC: 10-11 digits undashed, or dashed 4-5 / 3-4 / 1-2.
private val NDC_PATTERN = Regex("""^(\d{10,11}|\d{4,5}-\d{3,4}-\d{1,2})$""")

private fun String.isAcceptedDrugCode(acceptsDin: Boolean): Boolean =
    NDC_PATTERN.matches(this) || (acceptsDin && isDin())
