package org.rite.hl7.model.segment

import org.rite.hl7.model.SegmentDefinition
import org.rite.hl7.model.TypedSegment
import org.rite.hl7.model.ast.HL7Segment

/**
 * EQU — Equipment Detail. Standard HL7 EQU-1 is the equipment identifier
 * itself (no leading Set-ID) — but every wire example in
 * `plan/inventory/HL7_v2_5_1_INR_U06_Official_Specification.md` (and real
 * devices following it) prefixes a bare sequence number before the ID
 * composite. Detect which shape this row uses: a leading Set-ID is present
 * when field 1 has no second component (a bare value, not composite) while
 * field 2 does — i.e. field 2 looks like the real ID, not field 1.
 */
class EQUSegment(raw: HL7Segment) : TypedSegment(raw) {
    private val idField: Int get() = if (component(1, 2).isBlank() && component(2, 2).isNotBlank()) 2 else 1

    val equipmentId: String get() = component(idField, 1)
    val eventDateTime: String get() = fieldValue(idField + 1)
    val equipmentState: String get() = fieldValue(idField + 2)
    val localRemoteControlState: String get() = fieldValue(idField + 3)
    val alertLevel: String get() = fieldValue(idField + 4)

    companion object {
        const val NAME = "EQU"
        val Definition = SegmentDefinition(NAME) { EQUSegment(it) }
    }
}

/**
 * INV — Inventory Detail. The "INV" segment name is reused for three unrelated
 * vendor/project field maps — direction/dialect isn't in MSH-9 here (all three
 * appear under INU^U05 / INR^U05), so callers distinguish by shape:
 * - compact layout never sets past field 6
 * - device-sync (Parata) layout has NO leading Set-ID, item identifier at field 1
 * - count-result layout (this project's standard-first redesign) HAS a leading
 *   Set-ID and its item identifier composite is at field 2, not field 1
 * Check [fieldCount] and whether field 1 parses as a composite item identifier
 * vs. a bare Set-ID before deciding which accessor group applies.
 *
 * Compact layout (project-specific, warehouse/PMS inventory sync). Wire example:
 * `INV|1|00069015505^Drug Name^NDC|LOT-A|20251201|150|EA`
 * - INV-1 Set ID
 * - INV-2 Substance Identifier (CE) — NDC^name^codingSystem
 * - INV-3 Lot Number
 * - INV-4 Expiration Date
 * - INV-5 On-Hand Quantity
 * - INV-6 Quantity Units
 *
 * Device Inventory Sync layout (vendor cycle-count payload, e.g. Parata
 * robot INU^U05, no leading Set-ID). Wire example:
 * `INV|NDC001^LISINOPRIL 10MG^L|A^Active^HL70383|DRUG^Drug^HL70384|CELL_A1^Cell A1^L|||55|55|55|1|TAB^Tablets^UCUM|20260131|||LOTLIS001`
 * - INV-1 Item Identifier — NDC^name^codingSystem
 * - INV-2 Status — code^text^table
 * - INV-3 Item Type — code^text^table
 * - INV-4 Location — code^text
 * - INV-7/8/9 Quantity on hand / available / expected
 * - INV-10 Package size
 * - INV-11 Units — code^text^codeSystem
 * - INV-12 Expiration date
 * - INV-15 Lot number
 *
 * Count-Result layout (standard-first INU^U05 count response, see
 * `plan/inu-u05-field-spec.md`; built by [org.rite.hl7.builder.InventoryCountINVBuilder]).
 * Wire example:
 * `INV|1|00009-5134-03^LISINOPRIL 10MG TAB^L^SERIAL001^00300095134032|A^Active^HL70383|DRUG^Drug^HL70384||||50|50|50|TAB^Tablets^UCUM|20280630||||ABC123`
 * - INV-1 Set ID — every child OBX's OBX-4 points back at this value
 * - INV-2 Item Identifier — NDC^name^codingSystem^serial^GTIN
 * - INV-3 Status — code^text^table
 * - INV-4 Item Type — code^text^table
 * - INV-7/8/9 Quantity on hand / available / expected
 * - INV-10 Units — code^text^codeSystem
 * - INV-12 Expiration date
 * - INV-15 Lot number
 *
 * Bottle identity = INV-2.1 (NDC) + INV-15 (lot) + INV-12 (expiry) + INV-2.4
 * (serial), all four together — two rows sharing an NDC but differing in
 * lot/expiry/serial are distinct bottles, never merged.
 */
class INVSegment(raw: HL7Segment) : TypedSegment(raw) {
    val setId: String get() = fieldValue(1)
    val substanceCode: String get() = component(2, 1)
    val substanceName: String get() = component(2, 2)
    val substanceCodeSystem: String get() = component(2, 3)
    val lotNumber: String get() = fieldValue(3)
    val expirationDate: String get() = fieldValue(4)
    val inventoryOnHandQuantity: String get() = fieldValue(5)
    val units: String get() = fieldValue(6)

    // --- Device Inventory Sync accessors (Parata, no leading Set-ID) ---
    val deviceItemCode: String get() = component(1, 1)
    val deviceItemName: String get() = component(1, 2)
    val deviceStatusCode: String get() = component(2, 1)
    val deviceTypeCode: String get() = component(3, 1)
    val deviceLocationCode: String get() = component(4, 1)
    val deviceLocationText: String get() = component(4, 2)
    val deviceQuantityOnHand: String get() = fieldValue(7)
    val deviceQuantityAvailable: String get() = fieldValue(8)
    val deviceQuantityExpected: String get() = fieldValue(9)
    val devicePackageSize: String get() = fieldValue(10)
    val deviceUnitsCode: String get() = component(11, 1)
    val deviceUnitsText: String get() = component(11, 2)
    val deviceExpirationDate: String get() = fieldValue(12)
    val deviceLotNumber: String get() = fieldValue(15)

    // --- Count-Result accessors (standard-first INU^U05 response, has leading Set-ID) ---
    val countSetId: String get() = fieldValue(1)
    val countItemCode: String get() = component(2, 1)
    val countItemName: String get() = component(2, 2)
    val countCodingSystem: String get() = component(2, 3)
    /** Serial number — distinguishes two otherwise-identical bottles (same NDC/lot/expiry). */
    val countSerialNumber: String get() = component(2, 4)
    val countGtin: String get() = component(2, 5)
    val countStatusCode: String get() = component(3, 1)
    val countTypeCode: String get() = component(4, 1)
    val countQuantityOnHand: String get() = fieldValue(7)
    val countQuantityAvailable: String get() = fieldValue(8)
    val countQuantityExpected: String get() = fieldValue(9)
    val countUnitsCode: String get() = component(10, 1)
    val countUnitsText: String get() = component(10, 2)
    val countExpirationDate: String get() = fieldValue(12)
    val countLotNumber: String get() = fieldValue(15)

    companion object {
        const val NAME = "INV"
        val Definition = SegmentDefinition(NAME) { INVSegment(it) }

        /** Compact rows never populate past field 6; device-sync/count-result rows run to field 15. */
        const val DEVICE_SYNC_FIELD_THRESHOLD = 6
    }
}

/** QPD — Query Parameter Definition (used by QBP^Q11 / RSP^K11). */
class QPDSegment(raw: HL7Segment) : TypedSegment(raw) {
    val messageQueryName: String get() = fieldValue(1)        // QPD-1 (full)
    val queryNameCode: String get() = component(1, 1)         // QPD-1.1
    val queryTag: String get() = fieldValue(2)               // QPD-2
    val ndc: String get() = component(3, 1)                  // QPD-3.1
    val drugName: String get() = component(3, 2)
    val equipmentId: String get() = fieldValue(4)            // QPD-4

    companion object {
        const val NAME = "QPD"
        val Definition = SegmentDefinition(NAME) { QPDSegment(it) }
    }
}

/** RCP — Response Control Parameter (query). */
class RCPSegment(raw: HL7Segment) : TypedSegment(raw) {
    val queryPriority: String get() = fieldValue(1)          // I = immediate
    val quantityLimitedRequest: String get() = fieldValue(2)

    companion object {
        const val NAME = "RCP"
        val Definition = SegmentDefinition(NAME) { RCPSegment(it) }
    }
}

/** QAK — Query Acknowledgement (RSP^K11). */
class QAKSegment(raw: HL7Segment) : TypedSegment(raw) {
    val queryTag: String get() = fieldValue(1)
    val queryResponseStatus: String get() = fieldValue(2)    // OK / NF / AE
    val messageQueryName: String get() = fieldValue(3)

    companion object {
        const val NAME = "QAK"
        val Definition = SegmentDefinition(NAME) { QAKSegment(it) }
    }
}

/**
 * ZIN — Inventory count row (existing Z-segment).
 * Format: `ZIN|setId|dispenseType|quantity|lotNumber|expiry`
 * dispenseType ∈ { OPENED, SEALED, NA, EXPECTED_ON_HAND, ... }
 */
class ZINSegment(raw: HL7Segment) : TypedSegment(raw) {
    val setId: String get() = fieldValue(1)
    val dispenseType: String get() = fieldValue(2)
    val quantity: String get() = fieldValue(3)
    val lotNumber: String get() = fieldValue(4)
    val expiry: String get() = fieldValue(5)

    companion object {
        const val NAME = "ZIN"
        val Definition = SegmentDefinition(NAME) { ZINSegment(it) }
    }
}

/**
 * ZPR — Transaction priority (existing Z-segment).
 * Format: `ZPR|setId|PRIORITY|<STAT|URGENT|ROUTINE|TIMED>`
 */
class ZPRSegment(raw: HL7Segment) : TypedSegment(raw) {
    val setId: String get() = fieldValue(1)
    val qualifier: String get() = fieldValue(2)              // "PRIORITY"
    val priority: String get() = fieldValue(3)              // STAT/URGENT/ROUTINE/TIMED

    companion object {
        const val NAME = "ZPR"
        val Definition = SegmentDefinition(NAME) { ZPRSegment(it) }
    }
}

/**
 * BTS — Batch Trailer Segment (standard HL7 v2 control segment).
 *
 * Used here as a per-message trailer (not inside a BHS/BTS batch envelope,
 * since the receiving PMS parses one MSH-rooted message per MLLP frame) to
 * mark a large inventory sync split across multiple independently-ACKed
 * messages: which chunk this is, of how many, and this chunk's item total.
 *
 * Field map (per HL7 v2.5.1 Control chapter) — all three fields are numeric:
 * `BTS|batchMessageCount|batchTotalChunks|batchTotals`
 * - BTS-1 Batch Message Count — this chunk's 1-based index within the sync.
 * - BTS-2 Batch Comment — repurposed here to carry the total chunk count for
 *   this sync (a number, e.g. "10"), not free text.
 * - BTS-3 Batch Totals — this chunk's item total (repeatable per spec).
 */
class BTSSegment(raw: HL7Segment) : TypedSegment(raw) {
    val batchMessageCount: String get() = fieldValue(1)
    val batchComment: String get() = fieldValue(2)
    val batchTotals: String get() = fieldValue(3)

    companion object {
        const val NAME = "BTS"
        val Definition = SegmentDefinition(NAME) { BTSSegment(it) }
    }
}
