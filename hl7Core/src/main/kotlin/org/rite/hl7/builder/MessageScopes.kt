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
}

/** Scope for INR^U05 inventory count response (INV + ZIN rows). */
class InrU05Scope : MessageScope() {
    fun equ(block: (EQUBuilder) -> Unit) = add(EQUBuilder(), block)
    fun orc(block: (ORCBuilder) -> Unit) = add(ORCBuilder(), block)
    fun inv(block: (INVBuilder) -> Unit) = add(INVBuilder(), block)   // repeating
    fun zin(block: (ZINBuilder) -> Unit) = add(ZINBuilder(), block)   // repeating
    fun nte(block: (NTEBuilder) -> Unit) = add(NTEBuilder(), block)
}

/** Scope for INR^U06 inventory adjustment (INV + ZAD pairs from §11). */
class InrU06Scope : MessageScope() {
    fun equ(block: (EQUBuilder) -> Unit) = add(EQUBuilder(), block)
    fun orc(block: (ORCBuilder) -> Unit) = add(ORCBuilder(), block)
    fun inv(block: (INVBuilder) -> Unit) = add(INVBuilder(), block)   // repeating
    fun zad(block: (ZADBuilder) -> Unit) = add(ZADBuilder(), block)   // repeating
    fun nte(block: (NTEBuilder) -> Unit) = add(NTEBuilder(), block)
}

/** Scope for INU^U05 unsolicited inventory update. */
class InuU05Scope : MessageScope() {
    fun equ(block: (EQUBuilder) -> Unit) = add(EQUBuilder(), block)
    fun inv(block: (INVBuilder) -> Unit) = add(INVBuilder(), block)
    fun zin(block: (ZINBuilder) -> Unit) = add(ZINBuilder(), block)
    fun nte(block: (NTEBuilder) -> Unit) = add(NTEBuilder(), block)
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
