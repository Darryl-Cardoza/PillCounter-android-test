package com.dispensesure.retail.feature.hl7.parsing

import com.dispensesure.retail.core.region.Region
import com.dispensesure.retail.core.room.models.DispenseTxnEntity
import com.dispensesure.retail.core.room.models.enums.CountStatus
import org.rite.hl7.model.HL7Message
import org.rite.hl7.model.segment.ZDRSegment
import kotlin.math.roundToLong

/**
 * Why an inbound DISPENSESURE_NATIVE (ZDR) message is refused. [code] is the iOS `rawValue`
 * (camelCase) and goes into MSA-3 of the AR ACK.
 */
enum class NativeRejectReason(val code: String) {
    // Envelope
    NO_ZDR_SEGMENT("noZdrSegment"),
    MISSING_ENVELOPE_FIELD("missingEnvelopeField"),
    MULTIPLE_ZDR_SEGMENTS("multipleZdrSegments"),

    // Header
    INVALID_FLOW_TYPE("invalidFlowType"),
    INVALID_LAYOUT_VERSION("invalidLayoutVersion"),
    REQUEST_ID_MISMATCH("requestMessageIdMismatch"),

    // Drug codes and dispense fields
    INVALID_DRUG_CODE("invalidDrugCode"),
    MULTIPLE_DRUGS_ON_DISPENSE("multipleDrugsOnDispense"),
    MISSING_RX_NUMBER("missingRxNumber"),
    INVALID_QUANTITY("invalidQuantity"),

    // Order action against existing state
    ORDER_NOT_FOUND("orderNotFound"),
    ORDER_ALREADY_COMPLETED("orderAlreadyCompleted"),
    ORDER_NOT_ON_HOLD("orderNotOnHold"),
    NO_REFILLS_REMAINING("noRefillsRemaining"),
    REPLACE_NOT_SUPPORTED("replaceNotSupported"),
    UNSUPPORTED_ORDER_ACTION("unsupportedOrderAction"),
}

/**
 * Validates a whole inbound ZDR message BEFORE any handler runs, so a rejected message never
 * touches the database. Port of iOS `NativeZDRValidator`; first failure wins.
 * Quantity is validated here and only here.
 */
object NativeZdrValidator {

    private const val MAX_QUANTITY = 9999L
    private val KNOWN_LAYOUT_VERSIONS = setOf("1.0", "1.1", "1.2")
    private val KNOWN_ACTIONS = setOf("NW", "XO", "CA", "HD", "RL", "DC", "RF")
    private val ACTIONS_REQUIRING_QUANTITY = setOf("NW", "XO", "RF")

    // Region carries no HL7 drug-code digit range (its plainProductCodeLengths are barcode
    // lengths), so the iOS RegionConfig.validDrugCodeDigitRange values live here.
    private fun Region.validDrugCodeDigitRange(): IntRange = when (this) {
        Region.US -> 10..11 // NDC
        Region.CA -> 8..8 // DIN
    }

    private fun Region.drugCodeType(): String = when (this) {
        Region.US -> "NDC"
        Region.CA -> "DIN"
    }

    /**
     * @param existing the transaction for `NativeOrderKey.resolve(zdr)`, including a soft-deleted
     * one (Android has no DISCONTINUED status; a cancelled/discontinued order is `isDeleted`).
     * @param fillCount live fills already recorded for this Rx (used by RF).
     * @return the first [NativeRejectReason], or null to accept.
     */
    fun validate(
        message: HL7Message,
        existing: DispenseTxnEntity?,
        fillCount: Int,
        region: Region,
    ): NativeRejectReason? {
        validateEnvelope(message)?.let { return it }
        val zdr = message.segment<ZDRSegment>(ZDRSegment.NAME) ?: return NativeRejectReason.NO_ZDR_SEGMENT
        validateHeader(zdr, message)?.let { return it }
        validateDrugCodes(zdr, region)?.let { return it }
        if (flowOf(zdr) == "D") {
            validateDispenseFields(zdr)?.let { return it }
            return validateOrderAction(zdr, existing, fillCount)
        }
        return null
    }

    /** Drug codes from ZDR-05, trimmed, blanks dropped, duplicates removed keeping first-occurrence order. */
    fun dedupedDrugCodes(zdr: ZDRSegment): List<String> =
        zdr.drugs.map { it.ndc.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun flowOf(zdr: ZDRSegment) = zdr.flowTypeRaw.trim().uppercase()

    private fun validateEnvelope(message: HL7Message): NativeRejectReason? {
        val zdrCount = message.segmentsNamed(ZDRSegment.NAME).size
        if (zdrCount == 0) return NativeRejectReason.NO_ZDR_SEGMENT
        if (zdrCount > 1) return NativeRejectReason.MULTIPLE_ZDR_SEGMENTS
        val msh = message.header ?: return NativeRejectReason.MISSING_ENVELOPE_FIELD
        val envelope = listOf(
            msh.dateTimeOfMessage, // MSH-7
            msh.messageType, // MSH-9
            msh.messageControlId, // MSH-10
            msh.processingId, // MSH-11
            msh.versionId, // MSH-12
        )
        return if (envelope.any { it.isBlank() }) NativeRejectReason.MISSING_ENVELOPE_FIELD else null
    }

    private fun validateHeader(zdr: ZDRSegment, message: HL7Message): NativeRejectReason? {
        val flow = flowOf(zdr)
        if (flow != "D" && flow != "I") return NativeRejectReason.INVALID_FLOW_TYPE
        if (zdr.layoutVersion.trim() !in KNOWN_LAYOUT_VERSIONS) return NativeRejectReason.INVALID_LAYOUT_VERSION
        val requestId = zdr.requestMessageId.trim()
        if (requestId.isEmpty() || requestId != message.messageControlId.trim()) {
            return NativeRejectReason.REQUEST_ID_MISMATCH
        }
        if (flow == "D" && zdr.orderAction.isBlank()) return NativeRejectReason.UNSUPPORTED_ORDER_ACTION
        return null
    }

    private fun validateDrugCodes(zdr: ZDRSegment, region: Region): NativeRejectReason? {
        if (zdr.drugs.isEmpty()) return NativeRejectReason.INVALID_DRUG_CODE
        val codeType = zdr.drugCodeType.trim().uppercase()
        if (codeType.isNotEmpty() && codeType != region.drugCodeType()) return NativeRejectReason.INVALID_DRUG_CODE
        val digitRange = region.validDrugCodeDigitRange()
        for (drug in zdr.drugs) {
            val code = drug.ndc.trim()
            if (code.isEmpty()) return NativeRejectReason.INVALID_DRUG_CODE
            // Dashes are ignored when counting digits.
            if (code.count { it.isDigit() } !in digitRange) return NativeRejectReason.INVALID_DRUG_CODE
        }
        return null
    }

    private fun validateDispenseFields(zdr: ZDRSegment): NativeRejectReason? {
        // F1: a dispense carries exactly one drug (multi-drug stays valid on inventory).
        if (zdr.drugs.size > 1) return NativeRejectReason.MULTIPLE_DRUGS_ON_DISPENSE
        if (zdr.rxNumber.isBlank()) return NativeRejectReason.MISSING_RX_NUMBER
        val action = zdr.orderAction.trim().uppercase()
        val quantity = zdr.quantityToDispense.trim()
        if (quantity.isEmpty()) {
            return if (action in ACTIONS_REQUIRING_QUANTITY) NativeRejectReason.INVALID_QUANTITY else null
        }
        val parsed = quantity.toDoubleOrNull()?.takeIf { it.isFinite() }?.roundToLong()
            ?: return NativeRejectReason.INVALID_QUANTITY
        return if (parsed in 1..MAX_QUANTITY) null else NativeRejectReason.INVALID_QUANTITY
    }

    private fun validateOrderAction(
        zdr: ZDRSegment,
        existing: DispenseTxnEntity?,
        fillCount: Int,
    ): NativeRejectReason? {
        val action = zdr.orderAction.trim().uppercase()
        if (action == "RP") return NativeRejectReason.REPLACE_NOT_SUPPORTED
        if (action !in KNOWN_ACTIONS) return NativeRejectReason.UNSUPPORTED_ORDER_ACTION
        if (action == "NW") return null // a resend for a completed order creates a new fill
        if (action == "RF") return validateRefill(zdr, fillCount)

        val order = existing ?: return NativeRejectReason.ORDER_NOT_FOUND
        // iOS DISCONTINUED: Android has no such status, a discontinued/cancelled order is soft-deleted.
        val discontinued = order.isDeleted
        val completed = order.status == CountStatus.COMPLETED || order.status == CountStatus.FORCE_COMPLETED
        return when (action) {
            "CA" -> if (discontinued) NativeRejectReason.ORDER_ALREADY_COMPLETED else null
            "RL" -> if (discontinued || order.status != CountStatus.ON_HOLD) NativeRejectReason.ORDER_NOT_ON_HOLD else null
            else -> if (completed || discontinued) NativeRejectReason.ORDER_ALREADY_COMPLETED else null // XO, HD, DC
        }
    }

    private fun validateRefill(zdr: ZDRSegment, fillCount: Int): NativeRejectReason? {
        if (fillCount <= 0) return NativeRejectReason.ORDER_NOT_FOUND
        // Blank ZDR-19 means zero refills authorized.
        val allowed = zdr.refillsAllowed.trim().toIntOrNull()
        if (allowed == null || allowed <= 0) return NativeRejectReason.NO_REFILLS_REMAINING
        return if (fillCount <= allowed) null else NativeRejectReason.NO_REFILLS_REMAINING
    }
}
