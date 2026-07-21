package org.rite.hl7

import org.rite.hl7.builder.HL7Builder
import org.rite.hl7.model.HL7Message
import org.rite.hl7.model.SegmentDefinition
import org.rite.hl7.model.segment.ZADSegment
import org.rite.hl7.model.segment.ZSNSegment
import org.rite.hl7.model.segment.ZSVSegment
import org.rite.hl7.parser.HL7ParseResult
import org.rite.hl7.parser.HL7Parser
import org.rite.hl7.validation.AckBuilder
import org.rite.hl7.validation.HL7Validator
import org.rite.hl7.validation.ValidationConfig
import org.rite.hl7.validation.ValidationResult

/**
 * Convenience facade wiring a parser, builder, and validator together with the
 * PillCounter extension segments (ZSN/ZSV/ZAD) pre-registered.
 *
 * Apps that need finer control can build [HL7Parser]/[HL7Builder]/[HL7Validator]
 * directly; this is the batteries-included entry point.
 */
class HL7(
    version: String = "2.5",
    strictMode: Boolean = false,
    validationConfig: ValidationConfig = ValidationConfig.DEFAULT,
    extraSegments: List<SegmentDefinition> = emptyList(),
) {
    private val parser: HL7Parser = HL7Parser.Builder()
        .defaultVersion(version)
        .strictMode(strictMode)
        .also { b -> extensionDefinitions(extraSegments).forEach { b.registerCustomSegment(it) } }
        .build()

    private val builder: HL7Builder = HL7Builder.builder()
        .defaultVersion(version)
        .also { b -> extensionDefinitions(extraSegments).forEach { b.registerCustomSegment(it) } }
        .build()

    private val validator = HL7Validator(validationConfig)
    private val ackBuilder = AckBuilder(builder)

    fun parse(raw: String): HL7ParseResult = parser.parse(raw)
    fun parseMllp(bytes: ByteArray): HL7ParseResult = parser.parseMllp(bytes)

    fun build(): HL7Builder = builder
    fun validate(message: HL7Message): ValidationResult = validator.validate(message)

    /** Validates [message] and returns the encoded ACK^R01. */
    fun ack(message: HL7Message): String =
        ackBuilder.build(message, validator.validate(message)).encode()

    private fun extensionDefinitions(extra: List<SegmentDefinition>): List<SegmentDefinition> =
        listOf(ZSNSegment.Definition, ZSVSegment.Definition, ZADSegment.Definition) + extra
}
