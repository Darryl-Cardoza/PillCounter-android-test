package org.rite.hl7.validation

import org.rite.hl7.model.HL7Message
import org.rite.hl7.model.HL7MessageKind
import org.rite.hl7.model.segment.INVSegment
import org.rite.hl7.model.segment.OBXSegment
import org.rite.hl7.model.segment.ORCSegment
import org.rite.hl7.model.segment.QAKSegment
import org.rite.hl7.model.segment.QPDSegment
import org.rite.hl7.model.segment.RXESegment
import org.rite.hl7.model.segment.ZADSegment
import org.rite.hl7.model.segment.ZINSegment
import org.rite.hl7.model.segment.ZCCSegment
import org.rite.hl7.model.segment.ZNISegment
import org.rite.hl7.model.segment.ZPRSegment
import org.rite.hl7.model.segment.ZUISegment

/**
 * Validates a parsed [HL7Message] against required-field, message-shape, and
 * spec extension rules (§11/§12/§13), producing a [ValidationResult] that drives
 * the ACK code.
 *
 * Rules follow the project-authoritative Z-segment layout:
 *  - MSH: must be present, must carry a message type with a component
 *    separator, and a non-blank control ID (MSH-10) → else AR.
 *  - QBP^Q11: QPD-1 must equal the configured query name → else AR.
 *  - ZAD: adjustmentType and adjustmentReason must be recognized → else AR;
 *    a reason that requires a comment must have one → else AE.
 *  - RDE^O11 / RDE^O01: ZUI (Vivid) or ZNI (Eyecon), each validated on their
 *    own required fields (NDC, quantity, Rx number); otherwise ORC + at least
 *    one RXE required — ORC-1 must be a known order control code, ORC-2 must
 *    be present (any shape — Rx number format is not checked), and (for
 *    non-cancel orders) each RXE needs a valid-shaped NDC and a bounded
 *    whole-number quantity; any ZPR present must carry a known priority
 *    (case-insensitive). Patient name, route, order status, and HL7 version
 *    are content the PMS may omit or vary freely and are not validated.
 *  - INR^U06 without ZAD (plain count request): at least one OBX (each with a
 *    valid NDC and numeric value) or one RXE (each with a valid NDC) required;
 *    any ZIN quantity must not be negative.
 *  - MSH-9 = ACK, or any other unsupported message type/trigger → AR.
 *
 * Construct with a [ValidationConfig] to override the site defaults.
 */
class HL7Validator(private val config: ValidationConfig = ValidationConfig.DEFAULT) {

    fun validate(message: HL7Message): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()

        validateHeader(message, issues)
        validateQuery(message, issues)
        validateAdjustments(message, issues)
        validateDispense(message, issues)
        validateInventory(message, issues)
        validateInventorySync(message, issues)
        validateQueryResponse(message, issues)
        validateSupportedType(message, issues)

        return ValidationResult(issues)
    }

    private fun validateHeader(message: HL7Message, issues: MutableList<ValidationIssue>) {
        val header = message.header
        if (header == null) {
            issues += ValidationIssue(AckSeverity.REJECT, "Missing MSH segment", "MSH", "0", "100")
            return
        }
        if (header.messageCode.isBlank()) {
            issues += ValidationIssue(AckSeverity.REJECT, "Missing message type", "MSH", "9", "102")
        } else if (header.triggerEvent.isBlank()) {
            issues += ValidationIssue(AckSeverity.REJECT, "Invalid message type format", "MSH", "9", "103")
        }
        if (header.messageControlId.isBlank()) {
            issues += ValidationIssue(AckSeverity.REJECT, "Missing control ID (MSH-10)", "MSH", "10", "101")
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
            when {
                zad.adjustmentQuantity.isBlank() -> issues += ValidationIssue(
                    AckSeverity.REJECT, "Missing quantity in ZAD", "ZAD", "3", "207",
                )
                !isValidQuantity(zad.adjustmentQuantity) -> issues += ValidationIssue(
                    AckSeverity.REJECT, "Invalid quantity in ZAD", "ZAD", "3", "207",
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

    private fun validateDispense(message: HL7Message, issues: MutableList<ValidationIssue>) {
        if (message.messageCode != "RDE" || message.triggerEvent !in DISPENSE_TRIGGERS) return

        val zui = message.segment<ZUISegment>(ZUISegment.NAME)
        if (zui != null) {
            validateZui(zui, issues)
            return
        }
        val zni = message.segment<ZNISegment>(ZNISegment.NAME)
        if (zni != null) {
            validateZni(zni, issues)
            return
        }

        val orc = message.segment<ORCSegment>(ORCSegment.NAME)
        if (orc == null) {
            issues += ValidationIssue(AckSeverity.REJECT, "Missing ORC segment", "ORC", "0", "300")
            return
        }

        val control = orc.orderControl
        if (control !in config.knownOrderControlCodes) {
            issues += ValidationIssue(
                AckSeverity.REJECT,
                "Unsupported order control code: $control",
                "ORC", "1", "302",
            )
        }
        if (orc.placerOrderNumber.isBlank()) {
            issues += ValidationIssue(AckSeverity.REJECT, "Missing Rx number in ORC", "ORC", "2", "303")
        }

        // A cancel order identifies the order to cancel via ORC alone —
        // it carries no drug/quantity payload, so RXE isn't required.
        if (control == "CA") return

        val rxeSegments = message.segments<RXESegment>(RXESegment.NAME)
        if (rxeSegments.isEmpty()) {
            issues += ValidationIssue(AckSeverity.REJECT, "Missing RXE segment", "RXE", "0", "300")
            return
        }
        rxeSegments.forEachIndexed { index, rxe ->
            val position = index + 1
            when {
                rxe.giveCode.isBlank() -> issues += ValidationIssue(
                    AckSeverity.REJECT, "Missing NDC in RXE $position", "RXE", "2", "301",
                )
                !isValidNdc(rxe.giveCode) -> issues += ValidationIssue(
                    AckSeverity.REJECT, "Invalid NDC in RXE $position", "RXE", "2", "301",
                )
            }
            when {
                rxe.giveAmountMinimum.isBlank() -> issues += ValidationIssue(
                    AckSeverity.REJECT, "Missing quantity in RXE $position", "RXE", "3", "301",
                )
                !isValidQuantity(rxe.giveAmountMinimum) -> issues += ValidationIssue(
                    AckSeverity.REJECT, "Invalid quantity in RXE $position", "RXE", "3", "301",
                )
            }
        }

        message.segments<ZPRSegment>(ZPRSegment.NAME).forEach { zpr ->
            if (zpr.priority.uppercase() !in config.knownPriorities) {
                issues += ValidationIssue(
                    AckSeverity.REJECT, "Invalid priority: ${zpr.priority}", "ZPR", "3", "311",
                )
            }
        }
    }

    private fun validateZui(zui: ZUISegment, issues: MutableList<ValidationIssue>) {
        if (zui.ndc.isBlank() && zui.orderDispenseQuantity.isBlank() && zui.orderRxNumber.isBlank()) {
            issues += ValidationIssue(AckSeverity.REJECT, "Malformed ZUI segment", "ZUI", "0", "310")
            return
        }
        when {
            zui.ndc.isBlank() -> issues += ValidationIssue(AckSeverity.REJECT, "Missing NDC in ZUI", "ZUI", "1", "311")
            !isValidNdc(zui.ndc) -> issues += ValidationIssue(AckSeverity.REJECT, "Invalid NDC in ZUI", "ZUI", "1", "311")
        }
        when {
            zui.orderDispenseQuantity.isBlank() -> issues += ValidationIssue(
                AckSeverity.REJECT, "Missing quantity in ZUI", "ZUI", "5", "312",
            )
            !isValidQuantity(zui.orderDispenseQuantity) -> issues += ValidationIssue(
                AckSeverity.REJECT, "Invalid quantity in ZUI", "ZUI", "5", "312",
            )
        }
        if (zui.orderRxNumber.isBlank()) {
            issues += ValidationIssue(AckSeverity.REJECT, "Missing Rx number in ZUI", "ZUI", "6", "313")
        }
    }

    private fun validateZni(zni: ZNISegment, issues: MutableList<ValidationIssue>) {
        if (zni.ndc.isBlank() && zni.dispenseAmount.isBlank() && zni.fillerOrderNumber.isBlank()) {
            issues += ValidationIssue(AckSeverity.REJECT, "Malformed ZNI segment", "ZNI", "0", "310")
            return
        }
        when {
            zni.ndc.isBlank() -> issues += ValidationIssue(AckSeverity.REJECT, "Missing NDC in ZNI", "ZNI", "2", "311")
            !isValidNdc(zni.ndc) -> issues += ValidationIssue(AckSeverity.REJECT, "Invalid NDC in ZNI", "ZNI", "2", "311")
        }
        when {
            zni.dispenseAmount.isBlank() -> issues += ValidationIssue(
                AckSeverity.REJECT, "Missing quantity in ZNI", "ZNI", "13", "312",
            )
            !isValidQuantity(zni.dispenseAmount) -> issues += ValidationIssue(
                AckSeverity.REJECT, "Invalid quantity in ZNI", "ZNI", "13", "312",
            )
        }
        if (zni.fillerOrderNumber.isBlank()) {
            issues += ValidationIssue(AckSeverity.REJECT, "Missing Rx number in ZNI", "ZNI", "11", "313")
        }
    }

    /**
     * INR^U06 without ZAD: plain inventory count request, per
     * `plan/inventory/HL7_v2_5_1_INR_U06_Official_Specification.md`. EQU is
     * not consumed by the app and is not validated. Only INV-1 (NDC) / INV-2
     * (status) on every INV row are checked; INV-5 onward are request-side
     * and left empty.
     */
    private fun validateInventory(message: HL7Message, issues: MutableList<ValidationIssue>) {
        if (message.messageCode != "INR" || message.triggerEvent != INVENTORY_TRIGGER) return
        // ZAD-carrying messages are adjustments, already field-checked by
        // validateAdjustments; this rule is for plain count requests
        // (HL7MessageKind.INVENTORY_REQUEST) which carry no ZAD.
        if (message.segmentNamed(ZADSegment.NAME) != null) return

        val invSegments = message.segments<INVSegment>(INVSegment.NAME)
        if (invSegments.isEmpty()) {
            issues += ValidationIssue(
                AckSeverity.REJECT,
                "Missing INV segment for INR^$INVENTORY_TRIGGER",
                "INV", "0", "444",
            )
            return
        }
        invSegments.forEachIndexed { index, inv ->
            val position = index + 1
            when {
                inv.deviceItemCode.isBlank() -> issues += ValidationIssue(
                    AckSeverity.REJECT, "Missing NDC in INV $position", "INV", "1", "445",
                )
                !isValidNdc(inv.deviceItemCode) -> issues += ValidationIssue(
                    AckSeverity.REJECT, "Invalid NDC in INV $position", "INV", "1", "445",
                )
            }
            if (inv.deviceStatusCode.isBlank()) {
                issues += ValidationIssue(AckSeverity.REJECT, "Missing status in INV $position", "INV", "2", "446")
            }
        }
    }

    /** INR^U05 (count response) and INU^U05 (inventory update) share the same INV/ZIN row shape. */
    private fun validateInventorySync(message: HL7Message, issues: MutableList<ValidationIssue>) {
        if (message.kind != HL7MessageKind.INVENTORY_RESPONSE && message.kind != HL7MessageKind.INVENTORY_UPDATE) return

        val invSegments = message.segments<INVSegment>(INVSegment.NAME)
        // ZCC flattens the INV/OBX device-sync pair into one row — its own presence satisfies this.
        if (invSegments.isEmpty() && message.segmentNamed(ZCCSegment.NAME) == null) {
            issues += ValidationIssue(AckSeverity.REJECT, "Missing INV segment", "INV", "0", "410")
        } else if (invSegments.isNotEmpty()) {
            invSegments.forEachIndexed { index, inv ->
                val position = index + 1
                if (inv.fieldCount > INVSegment.DEVICE_SYNC_FIELD_THRESHOLD) {
                    when {
                        inv.deviceItemCode.isBlank() -> issues += ValidationIssue(
                            AckSeverity.REJECT, "Missing NDC in INV $position", "INV", "1", "411",
                        )
                        !isValidNdc(inv.deviceItemCode) -> issues += ValidationIssue(
                            AckSeverity.REJECT, "Invalid NDC in INV $position", "INV", "1", "411",
                        )
                    }
                    val qty = inv.deviceQuantityOnHand
                    if (qty.isNotBlank() && (qty.toDoubleOrNull() == null || qty.toDouble() < 0)) {
                        issues += ValidationIssue(
                            AckSeverity.REJECT, "Invalid quantity in INV $position", "INV", "7", "412",
                        )
                    }
                } else {
                    when {
                        inv.substanceCode.isBlank() -> issues += ValidationIssue(
                            AckSeverity.REJECT, "Missing NDC in INV $position", "INV", "2", "411",
                        )
                        !isValidNdc(inv.substanceCode) -> issues += ValidationIssue(
                            AckSeverity.REJECT, "Invalid NDC in INV $position", "INV", "2", "411",
                        )
                    }
                    val qty = inv.inventoryOnHandQuantity
                    when {
                        qty.isBlank() -> issues += ValidationIssue(
                            AckSeverity.REJECT, "Missing quantity in INV $position", "INV", "5", "412",
                        )
                        qty.toDoubleOrNull() == null || qty.toDouble() < 0 -> issues += ValidationIssue(
                            AckSeverity.REJECT, "Invalid quantity in INV $position", "INV", "5", "412",
                        )
                    }
                }
            }
        }

        message.segments<OBXSegment>(OBXSegment.NAME).forEachIndexed { index, obx ->
            val position = index + 1
            if (obx.observationId.isBlank()) {
                issues += ValidationIssue(
                    AckSeverity.REJECT, "Missing observation id in OBX $position", "OBX", "3", "413",
                )
            }
            val value = obx.observationValue
            if (obx.valueType.equals("NM", ignoreCase = true) &&
                value.isNotBlank() && (value.toDoubleOrNull() == null || value.toDouble() < 0)
            ) {
                issues += ValidationIssue(
                    AckSeverity.REJECT, "Invalid quantity in OBX $position", "OBX", "5", "414",
                )
            }
        }

        message.segments<ZCCSegment>(ZCCSegment.NAME).forEachIndexed { index, zcc ->
            val position = index + 1
            when {
                zcc.ndcCode.isBlank() -> issues += ValidationIssue(
                    AckSeverity.REJECT, "Missing NDC in ZCC $position", "ZCC", "1", "415",
                )
                !isValidNdc(zcc.ndcCode) -> issues += ValidationIssue(
                    AckSeverity.REJECT, "Invalid NDC in ZCC $position", "ZCC", "1", "415",
                )
            }
            val qty = zcc.totalQuantity
            if (qty.isNotBlank() && (qty.toDoubleOrNull() == null || qty.toDouble() < 0)) {
                issues += ValidationIssue(
                    AckSeverity.REJECT, "Invalid quantity in ZCC $position", "ZCC", "8", "416",
                )
            }
        }

        message.segments<ZINSegment>(ZINSegment.NAME).forEachIndexed { index, zin ->
            val qty = zin.quantity
            if (qty.isNotBlank() && (qty.toDoubleOrNull() == null || qty.toDouble() < 0)) {
                issues += ValidationIssue(
                    AckSeverity.REJECT, "Invalid quantity in ZIN ${index + 1}", "ZIN", "3", "402",
                )
            }
        }
    }

    private fun validateQueryResponse(message: HL7Message, issues: MutableList<ValidationIssue>) {
        if (message.messageCode != "RSP" || message.triggerEvent != "K11") return
        val qak = message.segment<QAKSegment>(QAKSegment.NAME)
        when {
            qak == null -> issues += ValidationIssue(AckSeverity.REJECT, "Missing QAK segment", "QAK", "0", "420")
            qak.queryResponseStatus !in QUERY_RESPONSE_STATUSES -> issues += ValidationIssue(
                AckSeverity.REJECT,
                "Invalid query response status: ${qak.queryResponseStatus}",
                "QAK", "2", "421",
            )
        }
    }

    private fun validateSupportedType(message: HL7Message, issues: MutableList<ValidationIssue>) {
        if (message.messageCode.isBlank() || message.triggerEvent.isBlank()) return // already flagged by validateHeader
        // ACK is the server's own outbound response type; it must never be accepted inbound.
        if (message.kind != HL7MessageKind.UNKNOWN && message.messageCode.uppercase() != "ACK") return
        issues += ValidationIssue(
            AckSeverity.REJECT,
            "Unsupported message type ${message.messageCode}^${message.triggerEvent}",
            "MSH", "9", "500",
        )
    }

    /**
     * A dispense/adjustment quantity must be a plain, bounded pill count —
     * no scientific notation or sign prefixes, no overflow. A decimal (e.g.
     * a half-pill count from a scale) is rounded to the nearest whole
     * number before the bounds check.
     */
    private fun isValidQuantity(value: String): Boolean {
        if (!QUANTITY_PATTERN.matches(value)) return false
        val d = value.toDoubleOrNull() ?: return false
        val rounded = kotlin.math.floor(d + 0.5).toLong()
        return rounded in 1..config.maxQuantity
    }

    companion object {
        // "001" is a known device quirk (e.g. VIVID) sending a zero instead of
        // the standard letter-O trigger for RDE^O01.
        private val DISPENSE_TRIGGERS = setOf("O11", "O01", "001")
        private const val INVENTORY_TRIGGER = "U06"

        // 11-digit NDC (project convention, no hyphens) or the standard
        // hyphenated 4-5/3-4/1-2 layout.
        private val NDC_PATTERN = Regex("^\\d{10,11}$|^\\d{4,5}-\\d{3,4}-\\d{1,2}$")

        // Plain non-negative number, optionally decimal — no 'e'/'E' or leading '+'.
        private val QUANTITY_PATTERN = Regex("^\\d+(\\.\\d+)?$")

        private val QUERY_RESPONSE_STATUSES = setOf("OK", "NF", "AE")

        private fun isValidNdc(value: String): Boolean = NDC_PATTERN.matches(value)
    }
}
