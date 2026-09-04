package org.rite.hl7.builder

/**
 * Message-scope DSLs. Each scope exposes only the segment blocks valid for its
 * message type; repeating segments append to the ordered builder list. The MSH
 * block is always available and its message type is set automatically by the
 * owning [HL7Builder] method.
 *
 * Usage (from the owning builder method):
 * ```
 * builder.rdsO13 {
 *     msh { it.sendingApplication = "..." }
 *     orc { it.orderControl = "RE" }
 *     rxd { it.dispenseGiveCode = "..." }
 *     zsn { it.setId = "1" }   // repeating
 * }
 * ```
 */
abstract class MessageScope {
    internal val builders = mutableListOf<HL7SegmentBuilder>()
    internal val mshBuilder = MSHBuilder()

    init {
        builders += mshBuilder
    }

    /** Configure the MSH header. */
    fun msh(block: (MSHBuilder) -> Unit) { block(mshBuilder) }

    protected fun <T : HL7SegmentBuilder> add(builder: T, block: (T) -> Unit): T {
        block(builder); builders += builder; return builder
    }
}

/** Scope for RDS^O13 dispense response (+ optional ZSN/ZSV from §10/§13). */
class RdsO13Scope : MessageScope() {
    fun pid(block: (PIDBuilder) -> Unit) = add(PIDBuilder(), block)
    fun orc(block: (ORCBuilder) -> Unit) = add(ORCBuilder(), block)
    fun rxe(block: (RXEBuilder) -> Unit) = add(RXEBuilder(), block)
    fun rxd(block: (RXDBuilder) -> Unit) = add(RXDBuilder(), block)
    fun rxr(block: (RXRBuilder) -> Unit) = add(RXRBuilder(), block)
    fun rxc(block: (RXCBuilder) -> Unit) = add(RXCBuilder(), block)
    fun nte(block: (NTEBuilder) -> Unit) = add(NTEBuilder(), block)
    fun obx(block: (OBXBuilder) -> Unit) = add(OBXBuilder(), block)   // repeating
    fun zsn(block: (ZSNBuilder) -> Unit) = add(ZSNBuilder(), block)   // repeating
    fun zsv(block: (ZSVBuilder) -> Unit) = add(ZSVBuilder(), block)
    fun zui(block: (ZUIDispenseBuilder) -> Unit) = add(ZUIDispenseBuilder(), block)
    fun zni(block: (ZNIBuilder) -> Unit) = add(ZNIBuilder(), block)
}

/** Scope for RDE^O11 dispense order. */
class RdeO11Scope : MessageScope() {
    fun pid(block: (PIDBuilder) -> Unit) = add(PIDBuilder(), block)
    fun pv1(block: (PV1Builder) -> Unit) = add(PV1Builder(), block)
    fun orc(block: (ORCBuilder) -> Unit) = add(ORCBuilder(), block)
    fun rxe(block: (RXEBuilder) -> Unit) = add(RXEBuilder(), block)
    fun rxr(block: (RXRBuilder) -> Unit) = add(RXRBuilder(), block)
    fun rxc(block: (RXCBuilder) -> Unit) = add(RXCBuilder(), block)
    fun nte(block: (NTEBuilder) -> Unit) = add(NTEBuilder(), block)
    fun zui(block: (ZUIOrderBuilder) -> Unit) = add(ZUIOrderBuilder(), block)
}

/** Scope for INR^U05 inventory count response (INV + ZIN rows). */
class InrU05Scope : MessageScope() {
    fun equ(block: (EQUBuilder) -> Unit) = add(EQUBuilder(), block)
    fun orc(block: (ORCBuilder) -> Unit) = add(ORCBuilder(), block)
    fun inv(block: (INVBuilder) -> Unit) = add(INVBuilder(), block)   // repeating
    fun zin(block: (ZINBuilder) -> Unit) = add(ZINBuilder(), block)   // repeating
    fun nte(block: (NTEBuilder) -> Unit) = add(NTEBuilder(), block)
}

/**
 * Scope for INR^U06 inventory adjustment (INV + ZAD pairs from §11).
 * ZIN rows are a project extension used alongside INV so PMS can see the
 * opened/sealed breakdown behind each INV row's combined on-hand total.
 */
class InrU06Scope : MessageScope() {
    fun equ(block: (EQUBuilder) -> Unit) = add(EQUBuilder(), block)
    fun orc(block: (ORCBuilder) -> Unit) = add(ORCBuilder(), block)
    fun inv(block: (INVBuilder) -> Unit) = add(INVBuilder(), block)   // repeating
    fun zin(block: (ZINBuilder) -> Unit) = add(ZINBuilder(), block)   // repeating, project extension
    fun zad(block: (ZADBuilder) -> Unit) = add(ZADBuilder(), block)   // repeating
    fun nte(block: (NTEBuilder) -> Unit) = add(NTEBuilder(), block)
    /** Per-message chunk trailer — present only when this message is one chunk of a split sync. */
    fun bts(block: (BTSBuilder) -> Unit) = add(BTSBuilder(), block)
}

/**
 * Scope for INU^U05 inventory update — sent as the response to a
 * PMS-initiated INR^U06 request (or unsolicited). INV carries each drug/lot
 * group's combined on-hand total; ZIN rows underneath break that total down
 * by dispenseType (OPENED/SEALED).
 *
 * ZAD is not part of the standard INU_U05 definition; it's kept here as a
 * project-specific extension so cycle-count adjustment reason/approver data
 * still travels on the response.
 */
class InuU05Scope : MessageScope() {
    fun equ(block: (EQUBuilder) -> Unit) = add(EQUBuilder(), block)
    fun orc(block: (ORCBuilder) -> Unit) = add(ORCBuilder(), block)
    fun inv(block: (INVBuilder) -> Unit) = add(INVBuilder(), block)   // repeating
    /** Vendor cycle-count payload row (e.g. Parata robot) — repeating, distinct layout from [inv]. */
    fun invDevice(block: (DeviceINVBuilder) -> Unit) = add(DeviceINVBuilder(), block)   // repeating
    /**
     * Standard-first count-result row (see `plan/inu-u05-field-spec.md`) — one
     * per physical bottle, repeating. Follow with [obx] rows whose `subId` is
     * set to this row's `setId` to attach sealed/open qty, image refs, and the
     * system/counted/adjustment breakdown to this specific bottle.
     */
    fun invCount(block: (InventoryCountINVBuilder) -> Unit) = add(InventoryCountINVBuilder(), block)   // repeating
    /** 24-field device inventory row with GS1 — repeating, non-standard extension. */
    fun zcc(block: (ZCCBuilder) -> Unit) = add(ZCCBuilder(), block)   // repeating
    fun obx(block: (OBXBuilder) -> Unit) = add(OBXBuilder(), block)   // repeating
    fun zin(block: (ZINBuilder) -> Unit) = add(ZINBuilder(), block)   // repeating
    fun zad(block: (ZADBuilder) -> Unit) = add(ZADBuilder(), block)   // repeating, non-standard extension
    fun nte(block: (NTEBuilder) -> Unit) = add(NTEBuilder(), block)
    /** Per-message chunk trailer — present only when this message is one chunk of a split sync. */
    fun bts(block: (BTSBuilder) -> Unit) = add(BTSBuilder(), block)
}

/** Scope for QBP^Q11 pre-count / stock-on-hand query (§12). */
class QbpQ11Scope : MessageScope() {
    fun qpd(block: (QPDBuilder) -> Unit) = add(QPDBuilder(), block)
    fun rcp(block: (RCPBuilder) -> Unit) = add(RCPBuilder(), block)
}

/** Scope for ACK^R01 acknowledgement. */
class AckScope : MessageScope() {
    fun msa(block: (MSABuilder) -> Unit) = add(MSABuilder(), block)
    fun err(block: (ERRBuilder) -> Unit) = add(ERRBuilder(), block)  // repeating
}

/** ZIN builder (existing inventory-count row). */
class ZINBuilder : HL7SegmentBuilder("ZIN") {
    var setId: String? = null
    var dispenseType: String? = null
    var quantity: String? = null
    var lotNumber: String? = null
    var expiry: String? = null
    override fun apply() {
        set(1, setId); set(2, dispenseType); set(3, quantity); set(4, lotNumber); set(5, expiry)
    }
}

/**
 * BTS — Batch Trailer Segment (standard control segment, HL7 v2.5.1 §2.19).
 * Used per-message to mark this message's position within a chunked inventory
 * sync: BTS-1 = this chunk's 1-based index (numeric), BTS-2 = total chunk
 * count for this sync (numeric — NOT free text), BTS-3 = this chunk's item
 * total (numeric).
 */
class BTSBuilder : HL7SegmentBuilder("BTS") {
    var batchMessageCount: String? = null
    var batchComment: String? = null
    var batchTotals: String? = null
    override fun apply() {
        set(1, batchMessageCount); set(2, batchComment); set(3, batchTotals)
    }
}
