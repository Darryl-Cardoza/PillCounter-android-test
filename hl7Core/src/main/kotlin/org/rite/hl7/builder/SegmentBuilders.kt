package org.rite.hl7.builder

import org.rite.hl7.encoding.HL7Delimiters
import org.rite.hl7.model.ast.HL7Component
import org.rite.hl7.model.ast.HL7Field
import org.rite.hl7.model.ast.HL7Segment
import org.rite.hl7.version.HL7Version
import org.rite.hl7.version.SegmentCapabilities

/**
 * MSH builder. MSH is special: field 1 is the field separator and field 2 is the
 * encoding characters, which are emitted literally by [HL7Segment.encode]. We
 * store MSH-2 as fields[0] (matching the parse layout) and MSH-3.. thereafter.
 */
class MSHBuilder : HL7SegmentBuilder("MSH") {
    var sendingApplication: String? = null
    var sendingFacility: String? = null
    var receivingApplication: String? = null
    var receivingFacility: String? = null
    var dateTimeOfMessage: String? = null
    var messageControlId: String? = null
    var processingId: String? = null
    var versionId: String? = null
    var countryCode: String? = null

    /** Set internally by message builders; full MSH-9, e.g. "RDS^O13". */
    internal var messageType: String? = null

    /** Builds the MSH segment directly (handles the MSH-1/MSH-2 quirk). */
    override fun build(delimiters: HL7Delimiters, version: HL7Version): HL7Segment {
        // fields layout for MSH: [encodingChars(MSH-2), MSH-3, MSH-4, ...]
        // Simple (single-value) fields keyed by index.
        val values = mapOf(
            3 to (sendingApplication ?: ""),
            4 to (sendingFacility ?: ""),
            5 to (receivingApplication ?: ""),
            6 to (receivingFacility ?: ""),
            7 to (dateTimeOfMessage ?: ""),
            10 to (messageControlId ?: ""),
            11 to (processingId ?: ""),
            12 to (versionId ?: version.wire),
            17 to (countryCode ?: ""),
        )
        // MSH-9 is composite (code^trigger) — split into components so the
        // separator is structural, not an escaped literal.
        val messageTypeComponents = (messageType ?: "").split(delimiters.component)

        val maxIndex = maxOf(values.keys.max(), 9)
        val cap = SegmentCapabilities.maxFields("MSH", version) ?: maxIndex
        val limit = minOf(maxIndex, cap)

        val fields = ArrayList<HL7Field>()
        fields += HL7Field.of(delimiters.encodingCharacters) // fields[0] = MSH-2
        for (n in 3..limit) {
            fields += when {
                n == 9 -> HL7Field(listOf(messageTypeComponents.map { HL7Component(listOf(it)) }))
                values.containsKey(n) -> HL7Field.of(values[n]!!)
                else -> HL7Field.EMPTY
            }
        }
        return HL7Segment("MSH", fields, delimiters)
    }
}

class PIDBuilder : HL7SegmentBuilder("PID") {
    var setId: String? = null;                 // PID-1
    var patientId: String? = null              // PID-3.1
    var familyName: String? = null             // PID-5.1
    var givenName: String? = null              // PID-5.2
    var dateOfBirth: String? = null            // PID-7
    var sex: String? = null                    // PID-8
    override fun apply() {
        set(1, setId); set(3, 1, patientId)
        set(5, 1, familyName); set(5, 2, givenName)
        set(7, dateOfBirth); set(8, sex)
    }
}

class PV1Builder : HL7SegmentBuilder("PV1") {
    var setId: String? = null
    var patientClass: String? = null
    var visitNumber: String? = null
    override fun apply() {
        set(1, setId); set(2, patientClass); set(19, 1, visitNumber)
    }
}

class ORCBuilder : HL7SegmentBuilder("ORC") {
    var orderControl: String? = null
    var placerOrderNumber: String? = null
    var fillerOrderNumber: String? = null
    var orderStatus: String? = null
    var dateTimeOfTransaction: String? = null
    var orderingProviderId: String? = null
    override fun apply() {
        set(1, orderControl); set(2, 1, placerOrderNumber); set(3, 1, fillerOrderNumber)
        set(5, orderStatus); set(9, dateTimeOfTransaction); set(12, 1, orderingProviderId)
    }
}

class RXEBuilder : HL7SegmentBuilder("RXE") {
    var giveCode: String? = null
    var giveName: String? = null
    var giveCodeSystem: String? = null
    var giveAmountMinimum: String? = null
    var giveUnits: String? = null
    var dispenseAmount: String? = null
    var prescriptionNumber: String? = null
    override fun apply() {
        set(2, 1, giveCode); set(2, 2, giveName); set(2, 3, giveCodeSystem)
        set(3, giveAmountMinimum); set(5, 1, giveUnits); set(10, dispenseAmount)
        set(15, prescriptionNumber)
    }
}

class RXDBuilder : HL7SegmentBuilder("RXD") {
    var dispenseSubIdCounter: String? = null
    var dispenseGiveCode: String? = null
    var dispenseGiveName: String? = null
    var dispenseGiveCodeSystem: String? = null
    var dateTimeDispensed: String? = null
    var actualDispenseAmount: String? = null
    var actualDispenseUnits: String? = null
    var prescriptionNumber: String? = null
    var dispensingProviderId: String? = null

    // RXD-10 is an XCN: ID^FamilyName^GivenName. Only the ID component used to be emitted, so a
    // receiver had nothing to render as an operator name — the Companion composes its Operator
    // column from the family and given names and shows nothing when both are absent.
    var dispensingProviderFamilyName: String? = null
    var dispensingProviderGivenName: String? = null
    var lotNumber: String? = null
    var expirationDate: String? = null
    override fun apply() {
        set(1, dispenseSubIdCounter)
        set(2, 1, dispenseGiveCode); set(2, 2, dispenseGiveName); set(2, 3, dispenseGiveCodeSystem)
        set(3, dateTimeDispensed); set(4, actualDispenseAmount); set(5, 1, actualDispenseUnits)
        set(7, prescriptionNumber)
        set(10, 1, dispensingProviderId)
        set(10, 2, dispensingProviderFamilyName); set(10, 3, dispensingProviderGivenName)
        set(15, lotNumber); set(16, expirationDate)
    }
}

class RXRBuilder : HL7SegmentBuilder("RXR") {
    var routeCode: String? = null
    var routeText: String? = null
    var administrationSiteCode: String? = null
    override fun apply() {
        set(1, 1, routeCode); set(1, 2, routeText); set(2, 1, administrationSiteCode)
    }
}

class RXCBuilder : HL7SegmentBuilder("RXC") {
    var componentType: String? = null
    var componentCode: String? = null
    var componentAmount: String? = null
    var componentUnits: String? = null
    override fun apply() {
        set(1, componentType); set(2, 1, componentCode); set(3, componentAmount); set(4, 1, componentUnits)
    }
}

class OBXBuilder : HL7SegmentBuilder("OBX") {
    var setId: String? = null
    var valueType: String? = null
    var observationId: String? = null
    var observationText: String? = null
    var observationValue: String? = null
    var units: String? = null
    var resultStatus: String? = null
    override fun apply() {
        set(1, setId); set(2, valueType); set(3, 1, observationId); set(3, 2, observationText)
        set(5, observationValue); set(6, 1, units); set(11, resultStatus)
    }
}

class EQUBuilder : HL7SegmentBuilder("EQU") {
    var equipmentId: String? = null
    var eventDateTime: String? = null
    var equipmentState: String? = null
    override fun apply() {
        set(1, 1, equipmentId); set(2, eventDateTime); set(3, equipmentState)
    }
}

/** Field positions per HL7 v2.5.1 standard INV layout (see [org.rite.hl7.model.segment.INVSegment]). */
class INVBuilder : HL7SegmentBuilder("INV") {
    var substanceCode: String? = null                 // NDC (INV-1.1)
    var substanceName: String? = null                 // INV-1.2
    var substanceCodeSystem: String? = null           // INV-1.3
    var lotNumber: String? = null                      // INV-16
    var expirationDate: String? = null                // INV-12
    var inventoryOnHandQuantity: String? = null        // INV-8 (Current Quantity)
    var units: String? = null                          // INV-11 (Quantity Units)
    override fun apply() {
        set(1, 1, substanceCode); set(1, 2, substanceName); set(1, 3, substanceCodeSystem)
        set(8, inventoryOnHandQuantity); set(11, units)
        set(12, expirationDate)
        set(16, lotNumber)
    }
}

class NTEBuilder : HL7SegmentBuilder("NTE") {
    var setId: String? = null
    var sourceOfComment: String? = null
    var comment: String? = null
    var commentType: String? = null
    override fun apply() {
        set(1, setId); set(2, sourceOfComment); set(3, comment); set(4, commentType)
    }
}

class MSABuilder : HL7SegmentBuilder("MSA") {
    var acknowledgmentCode: String? = null
    var messageControlId: String? = null
    var textMessage: String? = null
    override fun apply() {
        set(1, acknowledgmentCode); set(2, messageControlId); set(3, textMessage)
    }
}

class ERRBuilder : HL7SegmentBuilder("ERR") {
    var segmentId: String? = null
    var fieldPosition: String? = null
    var errorCode: String? = null
    var errorText: String? = null
    var severity: String? = null
    override fun apply() {
        set(2, 1, segmentId); set(2, 3, fieldPosition)
        set(3, 1, errorCode); set(3, 2, errorText); set(4, severity)
    }
}

class QPDBuilder : HL7SegmentBuilder("QPD") {
    var messageQueryName: String? = null
    var queryTag: String? = null
    var ndc: String? = null
    var drugName: String? = null
    var equipmentId: String? = null
    override fun apply() {
        set(1, 1, messageQueryName); set(2, queryTag)
        set(3, 1, ndc); set(3, 2, drugName); set(4, equipmentId)
    }
}

class RCPBuilder : HL7SegmentBuilder("RCP") {
    var queryPriority: String? = "I"
    var quantityLimitedRequest: String? = null
    override fun apply() {
        set(1, queryPriority); set(2, quantityLimitedRequest)
    }
}

class QAKBuilder : HL7SegmentBuilder("QAK") {
    var queryTag: String? = null
    var queryResponseStatus: String? = null
    var messageQueryName: String? = null
    override fun apply() {
        set(1, queryTag); set(2, queryResponseStatus); set(3, messageQueryName)
    }
}

// --- New extension Z-segment builders ---

class ZSNBuilder : HL7SegmentBuilder("ZSN") {
    var setId: String? = null
    var packageSerialNumber: String? = null
    var nationalDrugCode: String? = null
    var lotNumber: String? = null
    var expirationDate: String? = null
    var transactionType: String? = null
    var quantityFromThisStockItem: String? = null
    var captureSource: String? = null
    var captureTimestamp: String? = null
    override fun apply() {
        set(1, setId); set(2, packageSerialNumber); set(3, nationalDrugCode)
        set(4, lotNumber); set(5, expirationDate); set(6, transactionType)
        set(7, quantityFromThisStockItem); set(8, captureSource); set(9, captureTimestamp)
    }
}

class ZSVBuilder : HL7SegmentBuilder("ZSV") {
    var setId: String? = null
    var dispensedNdc: String? = null
    var scannedNdc: String? = null
    var validationResult: String? = null
    var scanSource: String? = null
    var validator: String? = null
    var validationTimestamp: String? = null
    var matchStrength: String? = null
    override fun apply() {
        set(1, setId); set(2, dispensedNdc); set(3, scannedNdc)
        set(4, validationResult); set(5, scanSource); set(6, validator)
        set(7, validationTimestamp); set(8, matchStrength)
    }
}

class ZADBuilder : HL7SegmentBuilder("ZAD") {
    var setId: String? = null
    var adjustmentType: String? = null
    var adjustmentQuantity: String? = null
    var adjustmentReason: String? = null
    var adjustmentDateTime: String? = null
    var approvedBy: String? = null
    var comment: String? = null
    override fun apply() {
        set(1, setId); set(2, adjustmentType); set(3, adjustmentQuantity)
        set(4, adjustmentReason); set(5, adjustmentDateTime); set(6, approvedBy)
        set(7, comment)
    }
}

/** ZUI order-data-packet builder (RDE^O11, PMSS → VIVID). */
class ZUIOrderBuilder : HL7SegmentBuilder("ZUI") {
    var ndc: String? = null
    var drugName: String? = null
    var patientFamilyName: String? = null
    var patientGivenName: String? = null
    var transactionOrderId: String? = null
    var dispenseQuantity: String? = null
    var rxNumber: String? = null
    var fillNumber: String? = null
    override fun apply() {
        set(1, ndc); set(2, drugName)
        set(3, 1, patientFamilyName); set(3, 2, patientGivenName)
        set(4, transactionOrderId); set(5, dispenseQuantity)
        set(6, rxNumber); set(7, fillNumber)
    }
}

/** ZUI pharmacy-dispense-message builder (RDS, VIVID → PMSS). */
class ZUIDispenseBuilder : HL7SegmentBuilder("ZUI") {
    var ndc: String? = null
    var vividUserName: String? = null
    var transactionOrderId: String? = null
    var rxNumber: String? = null
    var fillNumber: String? = null
    var dispensedQuantity: String? = null
    var transactionStatus: String? = null
    var drugImage: String? = null
    var drugLotNumber: String? = null
    var drugSerialNumber: String? = null
    var drugExpirationDate: String? = null
    override fun apply() {
        set(1, ndc); set(2, vividUserName); set(3, transactionOrderId)
        set(4, rxNumber); set(5, fillNumber); set(6, dispensedQuantity)
        set(7, transactionStatus); set(8, drugImage); set(9, drugLotNumber)
        set(10, drugSerialNumber); set(11, drugExpirationDate)
    }
}

/** ZNI Eyecon-to-Computer dispense result builder (fields 1-15 mirrored, result data from field 16). */
class ZNIBuilder : HL7SegmentBuilder("ZNI") {
    var mode: String? = null
    var ndc: String? = null
    var stockBottleBarcode: String? = null
    var drugName: String? = null
    var stockBottleVerification: String? = null
    var userName: String? = null
    var countType: String? = null
    var packetVersion: String? = null
    var patientFamilyName: String? = null
    var patientGivenName: String? = null
    var fillerOrderNumber: String? = null
    var substitutionStatus: String? = null
    var dispenseAmount: String? = null
    var prescriptionNumber: String? = null
    var fillNumber: String? = null
    var resultStatus: String? = null
    override fun apply() {
        set(1, mode); set(2, ndc); set(3, stockBottleBarcode); set(4, drugName)
        set(5, stockBottleVerification); set(6, userName); set(7, countType)
        set(9, packetVersion)
        set(10, 1, patientFamilyName); set(10, 2, patientGivenName)
        set(11, fillerOrderNumber); set(12, substitutionStatus)
        set(13, dispenseAmount); set(14, prescriptionNumber); set(15, fillNumber)
        set(16, resultStatus)
    }
}

/** ZUI dispense-message transaction status values (Pharmacy Dispense Message field 8). */
object ZuiTransactionStatus {
    const val DONE = "Done"
    const val CANCELLED = "Cancelled"
    const val PARTIAL = "Partial"
    const val OVERFILL = "Overfill"
}

/** ZAD-4 default reason codes (configurable per site; open string, not a hard enum). */
object ZadReasonCode {
    const val CYCLE_COUNT = "CYCLE_COUNT"
    const val PO_RECEIPT = "PO_RECEIPT"
    const val TRANSFER_IN = "TRANSFER_IN"
    const val TRANSFER_OUT = "TRANSFER_OUT"
    const val RETURN_TO_SUPPLIER = "RETURN_TO_SUPPLIER"
    const val BROKEN = "BROKEN"
    const val PHYSICAL_INVENTORY = "PHYSICAL_INVENTORY"
    const val EXPIRED = "EXPIRED"
    const val DAMAGED_IN_TRANSIT = "DAMAGED_IN_TRANSIT"
    const val TOTALLY_MADE_UP = "TOTALLY_MADE_UP"
}

/** ZSV-4 validation result values. */
object ZsvValidationResult {
    const val MATCH = "MATCH"
    const val SUBSTITUTION = "SUBSTITUTION"
    const val OVERRIDE = "OVERRIDE"
    const val MISMATCH = "MISMATCH"
}

/** ZSV-8 match strength values. Expected populated when validationResult is MATCH or SUBSTITUTION. */
object ZsvMatchStrength {
    /** 11-digit NDC exact match. */
    const val EXACT = "EXACT"
    /** GPI-equivalent generic match. */
    const val GENERIC = "GENERIC"
    /** 10-digit NDC fallback match. */
    const val NDC10 = "NDC10"
}

/** Scan/capture source values shared by ZSV-5 (scanSource) and ZSN-8 (captureSource). */
object ScanSource {
    /** 2D DataMatrix. */
    const val GS1 = "GS1"
    /** UPC-A/GTIN linear barcode. */
    const val NDC_LINEAR = "NDC_LINEAR"
    const val MANUAL = "MANUAL"
    /** ZSN-8 only. */
    const val UNKNOWN = "UNKNOWN"
}

/** ZSN-6 transaction type values. */
object ZsnTransactionType {
    const val DISPENSE = "D"
    const val RETURN = "R"
}
