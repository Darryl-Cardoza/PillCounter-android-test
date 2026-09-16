package org.rite.hl7.model.segment

import org.rite.hl7.model.SegmentDefinition
import org.rite.hl7.model.TypedSegment
import org.rite.hl7.model.ast.HL7Segment

/**
 * ZSN — Serial Number Capture (DSCSA). New §10 extension.
 *
 * Field map (project-authoritative):
 * `ZSN|setId|packageSerialNumber|nationalDrugCode|lotNumber|expirationDate|transactionType|
 * quantityFromThisStockItem|captureSource|captureTimestamp`
 * transactionType: D = dispense, R = return.
 * captureSource: GS1 / NDC_LINEAR / MANUAL / UNKNOWN.
 */
class ZSNSegment(raw: HL7Segment) : TypedSegment(raw) {
    val setId: String get() = fieldValue(1)
    val packageSerialNumber: String get() = fieldValue(2)
    val nationalDrugCode: String get() = fieldValue(3)
    val lotNumber: String get() = fieldValue(4)
    val expirationDate: String get() = fieldValue(5)
    val transactionType: String get() = fieldValue(6)
    val quantityFromThisStockItem: String get() = fieldValue(7)
    val captureSource: String get() = fieldValue(8)
    val captureTimestamp: String get() = fieldValue(9)

    companion object {
        const val NAME = "ZSN"
        /** Register on parser/builder via `registerCustomSegment(ZSNSegment.Definition)`. */
        val Definition = SegmentDefinition(NAME) { ZSNSegment(it) }
    }
}

/**
 * ZSV — Stock-bottle Validation segment. New §13 extension.
 *
 * Field map (project-authoritative):
 * `ZSV|setId|dispensedNdc|scannedNdc|validationResult|scanSource|validator|validationTimestamp|matchStrength`
 * dispensedNdc: echoes RXD-2.1 — the NDC the system expected.
 * scannedNdc: what the device actually read from the bottle barcode.
 * validationResult: MATCH / SUBSTITUTION / OVERRIDE / MISMATCH.
 * scanSource: GS1 (2D DataMatrix) / NDC_LINEAR (UPC-A/GTIN) / MANUAL.
 * validator: XCN — user_id^^first-name of the operator who performed the scan.
 * validationTimestamp: yyyyMMddHHmmss — when the scan was accepted.
 * matchStrength: EXACT (11-digit NDC) / GENERIC (GPI-equivalent) / NDC10 (10-digit fallback).
 *   Required when validationResult is MATCH or SUBSTITUTION.
 */
class ZSVSegment(raw: HL7Segment) : TypedSegment(raw) {
    val setId: String get() = fieldValue(1)
    val dispensedNdc: String get() = fieldValue(2)
    val scannedNdc: String get() = fieldValue(3)
    val validationResult: String get() = fieldValue(4)
    val scanSource: String get() = fieldValue(5)
    val validator: String get() = fieldValue(6)
    val validationTimestamp: String get() = fieldValue(7)
    val matchStrength: String get() = fieldValue(8)

    companion object {
        const val NAME = "ZSV"
        val Definition = SegmentDefinition(NAME) { ZSVSegment(it) }
    }
}

/**
 * ZAD — Inventory Adjustment. New §11 extension.
 *
 * Field map (project-authoritative):
 * `ZAD|setId|adjustmentType|adjustmentQuantity|adjustmentReason|adjustmentDateTime|approvedBy|comment`
 * adjustmentType (sign): + add, - subtract, O overwrite QOH.
 * adjustmentReason: see [org.rite.hl7.builder.ZadReasonCode] for default site reason codes.
 */
class ZADSegment(raw: HL7Segment) : TypedSegment(raw) {
    val setId: String get() = fieldValue(1)
    val adjustmentType: String get() = fieldValue(2)
    val adjustmentQuantity: String get() = fieldValue(3)
    val adjustmentReason: String get() = fieldValue(4)
    val adjustmentDateTime: String get() = fieldValue(5)
    val approvedBy: String get() = fieldValue(6)
    val comment: String get() = fieldValue(7)

    companion object {
        const val NAME = "ZAD"
        val Definition = SegmentDefinition(NAME) { ZADSegment(it) }
    }
}

/**
 * ZNI — Eyecon Native Interface order packet (Computer → Eyecon, "CtoE").
 * Source: Avery Weigh-Tronix GSE-02 "Eyecon Native Interface Protocol" (X25).
 *
 * Field map (minimum valid packet = fields 1-14; max = fields 1-15):
 * `ZNI|mode|ndc|stockBottleBarcode|drugName|stockBottleVerification|userName|countType||packetVersion|
 * patientName|fillerOrderNumber|substitutionStatus|dispenseAmount|prescriptionNumber|fillNumber`
 * mode: B/b buffer, I/i immediate, C cycle count, Q query inventory.
 * stockBottleVerification: A always / N never / U user-choice / blank defer to Eyecon setting.
 * substitutionStatus: Y/N/A (default A).
 * Field 8 is reserved for future use and always sent blank.
 *
 * Note: the EtoC result packet reuses the "ZNI" segment name with a different field map
 * (fields 1-15 mirrored, result data starting at field 16) — direction must be
 * determined from MSH-9 before assuming this order-packet shape applies.
 */
class ZNISegment(raw: HL7Segment) : TypedSegment(raw) {
    val mode: String get() = fieldValue(1)
    val ndc: String get() = fieldValue(2)
    val stockBottleBarcode: String get() = fieldValue(3)
    val drugName: String get() = fieldValue(4)
    val stockBottleVerification: String get() = fieldValue(5)
    val userName: String get() = fieldValue(6)
    val countType: String get() = fieldValue(7)
    // field 8 reserved for future use
    val packetVersion: String get() = fieldValue(9)
    val patientFamilyName: String get() = component(10, 1)
    val patientGivenName: String get() = component(10, 2)
    val fillerOrderNumber: String get() = fieldValue(11)
    val substitutionStatus: String get() = fieldValue(12)
    val dispenseAmount: String get() = fieldValue(13)
    val prescriptionNumber: String get() = fieldValue(14)
    val fillNumber: String get() = fieldValue(15)

    companion object {
        const val NAME = "ZNI"
        val Definition = SegmentDefinition(NAME) { ZNISegment(it) }
    }
}

/**
 * ZUI — Order Data Packet / Pharmacy Dispense Message. The "ZUI" segment name is
 * reused for two unrelated field maps depending on message type — direction must
 * be determined from MSH-9 before deciding which accessor group applies.
 *
 * Order Data Packet (RDE^O11, PMSS → VIVID):
 * `ZUI|ndc|drugName|patientName|transactionOrderId|dispenseQuantity|rxNumber|fillNumber`
 * ndc: required, 11-digit NDC.
 * patientName: family^given.
 * transactionOrderId: required, order ID or Rx number (numeric).
 * dispenseQuantity: required, numeric.
 * rxNumber: required, numeric.
 * fillNumber: optional, numeric.
 *
 * Pharmacy Dispense Message (RDS, VIVID → PMSS):
 * `ZUI|ndc|vividUserName|transactionOrderId|rxNumber|fillNumber|dispensedQuantity|transactionStatus|
 * drugImage|drugLotNumber|drugSerialNumber|drugExpirationDate`
 * vividUserName: "Anonymous" if in Anonymous Mode.
 * transactionStatus: Done / Cancelled / Partial / Overfill — see [org.rite.hl7.builder.ZuiTransactionStatus].
 * drugImage: optional, base64 encoded string.
 * drugLotNumber / drugSerialNumber / drugExpirationDate: optional GS1 fields; expirationDate is yyMMdd.
 */
class ZUISegment(raw: HL7Segment) : TypedSegment(raw) {
    /** Shared by both layouts (field 1 in both). */
    val ndc: String get() = fieldValue(1)

    // --- Order Data Packet (RDE^O11) accessors ---
    val orderDrugName: String get() = fieldValue(2)
    val orderPatientFamilyName: String get() = component(3, 1)
    val orderPatientGivenName: String get() = component(3, 2)
    val orderTransactionOrderId: String get() = fieldValue(4)
    val orderDispenseQuantity: String get() = fieldValue(5)
    val orderRxNumber: String get() = fieldValue(6)
    val orderFillNumber: String get() = fieldValue(7)

    // --- Pharmacy Dispense Message (RDS) accessors ---
    val dispenseVividUserName: String get() = fieldValue(2)
    val dispenseTransactionOrderId: String get() = fieldValue(3)
    val dispenseRxNumber: String get() = fieldValue(4)
    val dispenseFillNumber: String get() = fieldValue(5)
    val dispensedQuantity: String get() = fieldValue(6)
    val transactionStatus: String get() = fieldValue(7)
    val drugImage: String get() = fieldValue(8)
    val drugLotNumber: String get() = fieldValue(9)
    val drugSerialNumber: String get() = fieldValue(10)
    val drugExpirationDate: String get() = fieldValue(11)

    companion object {
        const val NAME = "ZUI"
        val Definition = SegmentDefinition(NAME) { ZUISegment(it) }
    }
}
