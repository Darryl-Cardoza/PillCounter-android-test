package org.rite.hl7.parser

import org.rite.hl7.encoding.Mllp
import org.rite.hl7.model.HL7Message
import org.rite.hl7.model.SegmentDefinition
import org.rite.hl7.model.SegmentRegistry
import org.rite.hl7.model.TypedSegment
import org.rite.hl7.version.HL7Version

/**
 * Parses raw HL7 text (or MLLP frames) into a typed [HL7Message].
 *
 * Construct via the fluent [Builder]; the resulting parser is immutable and
 * thread-safe — create once and inject.
 *
 * ```
 * val parser = HL7Parser.Builder()
 *     .defaultVersion("2.5")
 *     .registerCustomSegment(ZSNSegment.Definition)
 *     .strictMode(false)
 *     .build()
 * ```
 */
class HL7Parser private constructor(
    private val registry: SegmentRegistry,
    private val defaultVersion: HL7Version,
    private val strictMode: Boolean,
) {

    /** Parses raw HL7 text. Returns [HL7ParseResult.Success] or [HL7ParseResult.Failure]. */
    fun parse(raw: String): HL7ParseResult {
        if (raw.isBlank()) {
            return HL7ParseResult.Failure(listOf(HL7ParseError("Empty HL7 message")))
        }

        val lex = HL7Lexer.lex(raw)
        if (lex.errors.isNotEmpty() || lex.segments.isEmpty()) {
            return HL7ParseResult.Failure(lex.errors.map { HL7ParseError(it) })
        }

        val errors = mutableListOf<HL7ParseError>()
        val typed = mutableListOf<TypedSegment>()

        lex.segments.forEachIndexed { index, seg ->
            try {
                typed += registry.wrap(seg)
            } catch (e: Exception) {
                errors += HL7ParseError(
                    message = e.message ?: "Failed to parse segment",
                    segmentName = seg.name,
                    lineIndex = index,
                )
            }
        }

        val msh12 = typed.firstOrNull()?.raw?.fieldValue(12)
        val version = if (msh12.isNullOrBlank()) defaultVersion else HL7Version.from(msh12)

        val message = HL7Message(typed, lex.delimiters, version)

        return if (errors.isEmpty()) {
            HL7ParseResult.Success(message)
        } else if (strictMode) {
            HL7ParseResult.Failure(errors)
        } else {
            HL7ParseResult.Failure(errors, partialMessage = message)
        }
    }

    /** Parses an MLLP-framed byte array. */
    fun parseMllp(bytes: ByteArray): HL7ParseResult = parse(Mllp.strip(bytes))

    /** Fluent builder for [HL7Parser]. */
    class Builder {
        private val registry = SegmentRegistry()
        private var defaultVersion: HL7Version = HL7Version.DEFAULT
        private var strict: Boolean = false

        fun defaultVersion(version: String): Builder = apply {
            defaultVersion = HL7Version.from(version)
        }

        fun defaultVersion(version: HL7Version): Builder = apply {
            defaultVersion = version
        }

        /** Registers a custom (Z-)segment definition (e.g. `ZSNSegment.Definition`). */
        fun registerCustomSegment(definition: SegmentDefinition): Builder = apply {
            registry.register(definition)
        }

        /** When false (default), parse errors still return successfully-parsed segments. */
        fun strictMode(strict: Boolean): Builder = apply { this.strict = strict }

        fun build(): HL7Parser = HL7Parser(registry, defaultVersion, strict)
    }
}
