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
 * INV — Inventory Detail.
 *
 * Field map per the project reference / INR^U06 example:
 * `INV|1|00069015505^Drug Name^NDC|LOT-A|20251201|150|EA`
 */
class INVSegment(raw: HL7Segment) : TypedSegment(raw) {
    val setId: String get() = fieldValue(1)
    val substanceCode: String get() = component(2, 1)  // NDC (INV-2.1)
    val substanceName: String get() = component(2, 2)
    val substanceCodeSystem: String get() = component(2, 3)
    val lotNumber: String get() = fieldValue(3)
    val expirationDate: String get() = fieldValue(4)
    val inventoryOnHandQuantity: String get() = fieldValue(5)
    val units: String get() = fieldValue(6)

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
