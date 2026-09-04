package org.rite.hl7.builder

import org.rite.hl7.encoding.HL7Delimiters
import org.rite.hl7.model.HL7Message
import org.rite.hl7.model.SegmentDefinition
import org.rite.hl7.model.SegmentRegistry
import org.rite.hl7.model.TypedSegment
import org.rite.hl7.util.HL7Date
import org.rite.hl7.version.HL7Version

/** Thrown when a message fails build-time validation. */
class HL7BuildException(message: String) : Exception(message)

/**
 * Builds typed HL7 messages. Construct via [builder]; one method per supported
 * message type. Each method auto-sets MSH-9 (message type) and the version,
 * assembles the scope's segments, and returns an [HL7Message] you can
 * [HL7Message.encode].
 *
 * ```
 * val builder = HL7Builder.builder()
 *     .defaultVersion("2.5")
 *     .registerCustomSegment(ZSNSegment.Definition)
 *     .build()
 *
 * val raw = builder.rdsO13 {
 *     msh { it.sendingApplication = "PillCounter"; it.messageControlId = "1782200001" }
 *     rxd { it.dispenseGiveCode = "00093-0058-01"; it.actualDispenseAmount = "90" }
 *     zsn { it.setId = "1"; it.packageSerialNumber = "21N4F9XK0042" }
 * }.encode()
 * ```
 */
class HL7Builder private constructor(
    private val registry: SegmentRegistry,
    private val defaultVersion: HL7Version,
    private val delimiters: HL7Delimiters,
    private val fillTimestamps: Boolean,
) {

    fun rdsO13(block: RdsO13Scope.() -> Unit): HL7Message =
        assembleVersioned(RdsO13Scope().apply(block), pre25 = "RDS^O01", from25 = "RDS^O13")

    fun rdeO11(block: RdeO11Scope.() -> Unit): HL7Message =
        assembleVersioned(RdeO11Scope().apply(block), pre25 = "RDE^O01", from25 = "RDE^O11")

    fun inrU05(block: InrU05Scope.() -> Unit): HL7Message =
        assemble("INR^U05", InrU05Scope().apply(block))

    fun inrU06(block: InrU06Scope.() -> Unit): HL7Message =
        assemble("INR^U06", InrU06Scope().apply(block))

    fun inuU05(block: InuU05Scope.() -> Unit): HL7Message =
        assemble("INU^U05", InuU05Scope().apply(block))

    fun qbpQ11(block: QbpQ11Scope.() -> Unit): HL7Message =
        assemble("QBP^Q11", QbpQ11Scope().apply(block))

    fun ack(block: AckScope.() -> Unit): HL7Message =
        assemble("ACK^R01", AckScope().apply(block))

    /** Assembles a scope's builders into an [HL7Message], setting MSH-9 + version. */
    private fun assemble(messageType: String, scope: MessageScope): HL7Message {
        val msh = scope.mshBuilder
        msh.messageType = messageType
        if (msh.versionId == null) msh.versionId = defaultVersion.wire
        if (fillTimestamps && msh.dateTimeOfMessage == null) msh.dateTimeOfMessage = HL7Date.now()

        val version = HL7Version.from(msh.versionId)
        val typed: List<TypedSegment> = scope.builders.map { b ->
            registry.wrap(b.build(delimiters, version))
        }
        return HL7Message(typed, delimiters, version)
    }

    /**
     * Like [assemble], but picks the MSH-9 trigger event by resolved version:
     * [pre25] for HL7 < 2.5 (e.g. "RDS^O01"), [from25] for 2.5 and later
     * (e.g. "RDS^O13"). Version is resolved from the scope's MSH-12 if set,
     * else [defaultVersion].
     */
    private fun assembleVersioned(scope: MessageScope, pre25: String, from25: String): HL7Message {
        val version = HL7Version.from(scope.mshBuilder.versionId ?: defaultVersion.wire)
        val messageType = if (version.ordinal < HL7Version.V25.ordinal) pre25 else from25
        return assemble(messageType, scope)
    }

    /** Fluent builder for [HL7Builder]. */
    class Builder {
        private val registry = SegmentRegistry()
        private var defaultVersion: HL7Version = HL7Version.DEFAULT
        private var delimiters: HL7Delimiters = HL7Delimiters.DEFAULT
        private var fillTimestamps: Boolean = true

        fun defaultVersion(version: String): Builder = apply { defaultVersion = HL7Version.from(version) }
        fun defaultVersion(version: HL7Version): Builder = apply { defaultVersion = version }
        fun delimiters(d: HL7Delimiters): Builder = apply { delimiters = d }
        fun fillTimestamps(enabled: Boolean): Builder = apply { fillTimestamps = enabled }
        fun registerCustomSegment(definition: SegmentDefinition): Builder = apply { registry.register(definition) }

        fun build(): HL7Builder = HL7Builder(registry, defaultVersion, delimiters, fillTimestamps)
    }

    companion object {
        /** Entry point: `HL7Builder.builder()...build()`. */
        fun builder(): Builder = Builder()
    }
}
