package com.dispensesure.retail.feature.hl7.parsing

import com.dispensesure.retail.feature.hl7.parsing.model.OrderGroup
import org.rite.hl7.model.HL7Message
import org.rite.hl7.validation.HL7Validator
import org.rite.hl7.validation.ValidationConfig
import org.rite.hl7.validation.ValidationResult
import javax.inject.Inject
import javax.inject.Singleton

data class BusinessValidationResult(
    val isValid: Boolean,
    val reason: String? = null,
)

@Singleton
class Hl7Validator @Inject constructor() {

    private val libValidator: HL7Validator = run {
        val defaultConfig = ValidationConfig()
        val config = defaultConfig.copy(
            knownOrderControlCodes = setOf("NW", "RF", "CA", "HD", "RL", "DC", "XO", "RP"),
            knownPriorities = setOf("S", "A", "R", "T"),
        )
        HL7Validator(config)
    }

    fun validateStructure(message: HL7Message): ValidationResult =
        libValidator.validate(message)

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
