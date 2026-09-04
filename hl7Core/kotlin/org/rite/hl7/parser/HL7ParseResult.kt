package org.rite.hl7.parser

import org.rite.hl7.model.HL7Message

/** A single parse-time problem. */
data class HL7ParseError(
    val message: String,
    val segmentName: String? = null,
    val lineIndex: Int? = null,
)

/**
 * Outcome of [HL7Parser.parse].
 *
 * - [Success] carries the parsed [message].
 * - [Failure] carries the collected [errors] and, in non-strict mode, a
 *   [partialMessage] containing the segments that parsed successfully.
 *
 * Swift pattern-matches this as `if case .success(let message) = ...`.
 */
sealed class HL7ParseResult {
    data class Success(val message: HL7Message) : HL7ParseResult()
    data class Failure(
        val errors: List<HL7ParseError>,
        val partialMessage: HL7Message? = null,
    ) : HL7ParseResult()

    val isSuccess: Boolean get() = this is Success

    /** The message if successful, else the partial message (may be null). */
    val messageOrNull: HL7Message?
        get() = when (this) {
            is Success -> message
            is Failure -> partialMessage
        }
}
