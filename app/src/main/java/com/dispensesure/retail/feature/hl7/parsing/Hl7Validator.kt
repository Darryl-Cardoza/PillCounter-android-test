package com.dispensesure.retail.feature.hl7.parsing

import com.dispensesure.retail.core.region.RegionProvider
import com.dispensesure.retail.feature.hl7.parsing.model.OrderGroup
import org.rite.hl7.model.HL7Message
import org.rite.hl7.model.segment.RXESegment
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
            knownPriorities = setOf("High", "Medium", "Low"),
        )
        HL7Validator(config)
    }

    fun validateStructure(message: HL7Message): ValidationResult {
        val libraryResult = libValidator.validate(message)
        if (!regionProvider.current.hl7OrdersAcceptDin) return libraryResult
        // The library only knows NDC formats; a Canadian order's RXE-2 can be an 8-digit DIN.
        val rxeSegments = message.segments<RXESegment>(RXESegment.NAME)
        return ValidationResult(libraryResult.issues.filterNot { issue -> issue.isInvalidNdcForDin(rxeSegments) })
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

private val INVALID_NDC_IN_RXE = Regex("""^Invalid NDC in RXE (\d+)""")
private val DIN_PATTERN = Regex("""^\d{8}$""")

// The library's "Invalid NDC in RXE n" (n counts from 1) raised for an RXE-2 that is a DIN.
private fun ValidationIssue.isInvalidNdcForDin(rxeSegments: List<RXESegment>): Boolean {
    val rxeNumber = INVALID_NDC_IN_RXE.find(errorText)?.groupValues?.get(1)?.toIntOrNull() ?: return false
    val giveCode = rxeSegments.getOrNull(rxeNumber - 1)?.giveCode?.trim() ?: return false
    return DIN_PATTERN.matches(giveCode)
}
