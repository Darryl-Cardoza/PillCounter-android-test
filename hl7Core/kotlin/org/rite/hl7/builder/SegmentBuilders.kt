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
    var dispensingProviderFamilyName: String? = null
    var dispensingProviderGivenName: String? = null
    var lotNumber: String? = null
    var expirationDate: String? = null
    override fun apply() {
        set(1, dispenseSubIdCounter)
        set(2, 1, dispenseGiveCode); set(2, 2, dispenseGiveName); set(2, 3, dispenseGiveCodeSystem)
        set(3, dateTimeDispensed); set(4, actualDispenseAmount); set(5, 1, actualDispenseUnits)
        set(7, prescriptionNumber)
        set(10, 1, dispensingProviderId); set(10, 2, dispensingProviderFamilyName); set(10, 3, dispensingProviderGivenName)
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
    /** OBX-4 — Observation Sub-ID. Set to a parent INV row's Set-ID (INV-1) to link this OBX to that bottle; leave null for message-level OBX rows (e.g. OPERATOR_ID/OPERATOR_NAME). */
    var subId: String? = null
    var observationValue: String? = null
    var units: String? = null
    var resultStatus: String? = null
    override fun apply() {
        set(1, setId); set(2, valueType); set(3, 1, observationId); set(3, 2, observationText)
        set(4, subId); set(5, observationValue); set(6, 1, units); set(11, resultStatus)
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

/** Field positions per [org.rite.hl7.model.segment.INVSegment]'s project-specific compact layout. */
class INVBuilder : HL7SegmentBuilder("INV") {
    var setId: String? = null                          // INV-1
    var substanceCode: String? = null                   // NDC (INV-2.1)
    var substanceName: String? = null                   // INV-2.2
    var substanceCodeSystem: String? = null             // INV-2.3
    var lotNumber: String? = null                       // INV-3
    var expirationDate: String? = null                  // INV-4
    var inventoryOnHandQuantity: String? = null         // INV-5
    var units: String? = null                           // INV-6
    override fun apply() {
        set(1, setId)
        set(2, 1, substanceCode); set(2, 2, substanceName); set(2, 3, substanceCodeSystem)
        set(3, lotNumber); set(4, expirationDate)
        set(5, inventoryOnHandQuantity); set(6, units)
    }
}

/**
 * INV — Device Inventory Sync row (vendor cycle-count payload, e.g. Parata
 * robot INU^U05). Distinct field layout from [INVBuilder]'s project-compact
 * INV; both share the wire segment name "INV" — see [org.rite.hl7.model.segment.INVSegment]
 * for how readers tell the two apart.
 */
class DeviceINVBuilder : HL7SegmentBuilder("INV") {
    var itemCode: String? = null                        // INV-1.1
    var itemName: String? = null                         // INV-1.2
    var statusCode: String? = null                        // INV-2.1
    var statusText: String? = null                        // INV-2.2
    var typeCode: String? = null                          // INV-3.1
    var typeText: String? = null                          // INV-3.2
    var locationCode: String? = null                      // INV-4.1
    var locationText: String? = null                      // INV-4.2
    var quantityOnHand: String? = null                   // INV-7
    var quantityAvailable: String? = null                // INV-8
    var quantityExpected: String? = null                 // INV-9
    var packageSize: String? = null                      // INV-10
    var unitsCode: String? = null                         // INV-11.1
    var unitsText: String? = null                         // INV-11.2
    var expirationDate: String? = null                   // INV-12
    var lotNumber: String? = null                         // INV-15
    override fun apply() {
        set(1, 1, itemCode); set(1, 2, itemName)
        set(2, 1, statusCode); set(2, 2, statusText)
        set(3, 1, typeCode); set(3, 2, typeText)
        set(4, 1, locationCode); set(4, 2, locationText)
        set(7, quantityOnHand); set(8, quantityAvailable); set(9, quantityExpected)
        set(10, packageSize)
        set(11, 1, unitsCode); set(11, 2, unitsText)
        set(12, expirationDate)
        set(15, lotNumber)
    }
}

/**
 * INV — Inventory Count Result row (standard-first redesign, INU^U05 count
 * response per `plan/inu-u05-field-spec.md`). Distinct field layout from
 * [DeviceINVBuilder] (Parata-style, no leading Set-ID) and [INVBuilder]
 * (project-compact) — all three share the wire segment name "INV"; see
 * [org.rite.hl7.model.segment.INVSegment] for how readers tell them apart.
 *
 * Wire example:
 * `INV|1|00009-5134-03^LISINOPRIL 10MG TAB^L^SERIAL001^00300095134032|A^Active^HL70383|DRUG^Drug^HL70384||||50|50|50|TAB^Tablets^UCUM|20280630||||ABC123`
 */
class InventoryCountINVBuilder : HL7SegmentBuilder("INV") {
    var setId: String? = null                            // INV-1
    var itemCode: String? = null                          // INV-2.1 (NDC)
    var itemName: String? = null                          // INV-2.2
    var codingSystem: String? = "L"                       // INV-2.3
    var serialNumber: String? = null                      // INV-2.4 — distinguishes otherwise-identical bottles
    var gtin: String? = null                              // INV-2.5
    var statusCode: String? = "A"                         // INV-3.1
    var statusText: String? = "Active"                    // INV-3.2
    var statusTable: String? = "HL70383"                  // INV-3.3
    var typeCode: String? = "DRUG"                        // INV-4.1
    var typeText: String? = "Drug"                        // INV-4.2
    var typeTable: String? = "HL70384"                    // INV-4.3
    var quantityOnHand: String? = null                   // INV-7 — sealed + open combined
    var quantityAvailable: String? = null                // INV-8
    var quantityExpected: String? = null                 // INV-9
    var unitsCode: String? = null                         // INV-10.1
    var unitsText: String? = null                         // INV-10.2
    var unitsCodeSystem: String? = null                   // INV-10.3
    var expirationDate: String? = null                   // INV-12
    var lotNumber: String? = null                         // INV-15
    override fun apply() {
        set(1, setId)
        set(2, 1, itemCode); set(2, 2, itemName); set(2, 3, codingSystem)
        set(2, 4, serialNumber); set(2, 5, gtin)
        set(3, 1, statusCode); set(3, 2, statusText); set(3, 3, statusTable)
        set(4, 1, typeCode); set(4, 2, typeText); set(4, 3, typeTable)
        set(7, quantityOnHand); set(8, quantityAvailable); set(9, quantityExpected)
        set(10, 1, unitsCode); set(10, 2, unitsText); set(10, 3, unitsCodeSystem)
        set(12, expirationDate)
        set(15, lotNumber)
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

/** Field positions per [org.rite.hl7.model.segment.ZCCSegment] — 24-field device inventory row with GS1. */
class ZCCBuilder : HL7SegmentBuilder("ZCC") {
    var ndcCode: String? = null
    var drugName: String? = null
    var drugType: String? = null
    var manufacturer: String? = null
    var manufacturerCode: String? = null
    var gtin: String? = null
    var cellLocation: String? = null
    var totalQuantity: String? = null
    var sealedCount: String? = null
    var sealedContainers: String? = null
    var openCount: String? = null
    var openContainers: String? = null
    var lotNumber: String? = null
    var serialNumber: String? = null
    var expirationDate: String? = null
    var manufacturingDate: String? = null
    var unitOfMeasureCode: String? = null
    var unitOfMeasureText: String? = null
    var unitOfMeasureCodeSystem: String? = null
    var packageSize: String? = null
    var reorderLevel: String? = null
    var stockStatus: String? = null
    var imagePaths: List<String>? = null
    var countStatus: String? = null
    var operatorName: String? = null
    var notes: String? = null
    override fun apply() {
        set(1, ndcCode); set(2, drugName); set(3, drugType)
        set(4, manufacturer); set(5, manufacturerCode); set(6, gtin); set(7, cellLocation)
        set(8, totalQuantity); set(9, sealedCount); set(10, sealedContainers)
        set(11, openCount); set(12, openContainers)
        set(13, lotNumber); set(14, serialNumber)
        set(15, expirationDate); set(16, manufacturingDate)
        set(17, 1, unitOfMeasureCode); set(17, 2, unitOfMeasureText); set(17, 3, unitOfMeasureCodeSystem)
        set(18, packageSize); set(19, reorderLevel); set(20, stockStatus)
        setRepeated(21, imagePaths)
        set(22, countStatus); set(23, operatorName); set(24, notes)
    }
}

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
