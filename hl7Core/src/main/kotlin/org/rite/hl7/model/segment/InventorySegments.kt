package org.rite.hl7.model.segment

import org.rite.hl7.model.SegmentDefinition
import org.rite.hl7.model.TypedSegment
import org.rite.hl7.model.ast.HL7Segment

/** EQU — Equipment Detail. */
class EQUSegment(raw: HL7Segment) : TypedSegment(raw) {
    val equipmentId: String get() = component(1, 1)
    val eventDateTime: String get() = fieldValue(2)
    val equipmentState: String get() = fieldValue(3)
    val localRemoteControlState: String get() = fieldValue(4)
    val alertLevel: String get() = fieldValue(5)

    companion object {
        const val NAME = "EQU"
        val Definition = SegmentDefinition(NAME) { EQUSegment(it) }
    }
}

/**
 * INV — Inventory Detail (HL7 v2.5.1 §Clinical Laboratory Automation).
 *
 * This PMS sends a compressed variant of the standard field layout — it
 * does not populate the standard INV-7..INV-11 quantity/units fields.
 * Actual wire example:
 * `INV|0527-3161-32^Olanzapine^NDC|||320B`
 *
 * Field map (standard positions, values as actually sent by this PMS):
 * - INV-1 Substance Identifier (CE) — NDC^name^codingSystem
 * - INV-2 Substance Status — required by spec, left empty by this PMS
 * - INV-3 Substance Type — left empty by this PMS
 * - INV-4 Inventory Container Identifier — repurposed by this PMS to carry a
 *   merged quantity+unit token (e.g. "320B" = quantity 320, unit "B")
 *   instead of a container id, since INV-7..INV-11 aren't populated.
 */
class INVSegment(raw: HL7Segment) : TypedSegment(raw) {
    val substanceCode: String get() = component(1, 1)  // NDC (INV-1.1)
    val substanceName: String get() = component(1, 2)
    val substanceCodeSystem: String get() = component(1, 3)
    val lotNumber: String get() = fieldValue(16)        // Manufacturer Lot Number (INV-16)
    val expirationDate: String get() = fieldValue(12)   // Expiration Date/Time (INV-12)

    /** Raw merged quantity+unit token this PMS sends in INV-4, e.g. "320B". */
    private val quantityUnitToken: String get() = fieldValue(4)

    val inventoryOnHandQuantity: String
        get() = quantityUnitToken.takeWhile { it.isDigit() }

    val units: String
        get() = quantityUnitToken.dropWhile { it.isDigit() }

    companion object {
        const val NAME = "INV"
        val Definition = SegmentDefinition(NAME) { INVSegment(it) }
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
