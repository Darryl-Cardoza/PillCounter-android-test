package org.rite.hl7.validation

/**
 * Site-configurable inputs for the validator. Defaults follow the PillCounter
 * spec; partners may override per site (the spec's "downloadable text file" model).
 */
data class ValidationConfig(
    /** Adjustment reason codes the PMS recognizes (ZAD-4 / adjustmentReason). */
    val knownAdjustmentReasons: Set<String> = DEFAULT_ADJUSTMENT_REASONS,
    /** Adjustment types the PMS recognizes (ZAD-2 / adjustmentType). */
    val knownAdjustmentTypes: Set<String> = DEFAULT_ADJUSTMENT_TYPES,
    /** The literal query name QPD-1 must carry for a stock-on-hand query. */
    val expectedQueryName: String = DEFAULT_QUERY_NAME,
    /** Adjustment reasons that require a non-empty comment (currently none in the user layout). */
    val commentRequiredReasons: Set<String> = emptySet(),
    /** ORC-1 order control codes the PMS recognizes for dispense orders. */
    val knownOrderControlCodes: Set<String> = DEFAULT_ORDER_CONTROL_CODES,
    /** ZPR-3 priority values the PMS recognizes (matched case-insensitively). */
    val knownPriorities: Set<String> = DEFAULT_PRIORITIES,
    /** Maximum accepted whole-pill dispense/adjustment quantity. */
    val maxQuantity: Int = DEFAULT_MAX_QUANTITY,
) {
    companion object {
        val DEFAULT_ADJUSTMENT_REASONS = setOf(
            "CYCLE_COUNT", "PO_RECEIPT", "TRANSFER_IN", "TRANSFER_OUT",
            "RETURN_TO_SUPPLIER", "BROKEN", "PHYSICAL_INVENTORY", "EXPIRED",
            "DAMAGED_IN_TRANSIT", "LOSS",
        )
        val DEFAULT_ADJUSTMENT_TYPES = setOf("LOSS", "GAIN", "ADD", "SUBTRACT", "OVERWRITE", "+", "-", "O")
        const val DEFAULT_QUERY_NAME = "IHE PCC StockOnHandQuery"
        val DEFAULT_ORDER_CONTROL_CODES = setOf("NW", "XO", "CA")
        val DEFAULT_PRIORITIES = setOf("HIGH", "ROUTINE")
        const val DEFAULT_MAX_QUANTITY = 9999

        val DEFAULT = ValidationConfig()
    }
}
