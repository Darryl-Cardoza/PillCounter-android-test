package org.rite.hl7.parser

import org.rite.hl7.encoding.HL7Delimiters
import org.rite.hl7.model.ast.HL7Segment

/**
 * Splits a raw HL7 message string into generic [HL7Segment]s.
 *
 * Responsibilities:
 *  - normalize line endings (`\r\n`, `\n` → `\r`) and split into segment lines
 *  - read the delimiter set from the MSH line (never assumed)
 *  - parse each line into a generic segment using those delimiters
 *
 * It does NO domain interpretation — that is the parser/typed layer's job.
 */
object HL7Lexer {

    data class LexResult(
        val segments: List<HL7Segment>,
        val delimiters: HL7Delimiters,
        /** Lines that could not be lexed (e.g. before MSH); empty on success. */
        val errors: List<String> = emptyList(),
    )

    fun lex(raw: String): LexResult {
        val lines = raw
            .replace("\r\n", "\r")
            .replace("\n", "\r")
            .split("\r")
            .filter { it.isNotBlank() }

        if (lines.isEmpty()) {
            return LexResult(emptyList(), HL7Delimiters.DEFAULT, listOf("Empty HL7 message"))
        }

        val mshLine = lines.first()
        if (!mshLine.startsWith("MSH")) {
            return LexResult(
                emptyList(),
                HL7Delimiters.DEFAULT,
                listOf("First segment must be MSH, found: ${mshLine.take(10)}"),
            )
        }

        val delimiters = HL7Delimiters.fromMshLine(mshLine)
        val segments = lines.map { line -> HL7Segment.parse(normalizeName(line), delimiters) }
        return LexResult(segments, delimiters)
    }

    /**
     * Normalizes vendor-prefixed OBX variants (e.g. "FOBX" → "OBX"); some
     * systems prefix OBX with the result-status character. The segment still
     * carries its status in OBX-11 so no field shift is needed — we only rewrite
     * the leading 4-char name "?OBX" to "OBX".
     */
    private fun normalizeName(line: String): String {
        // "FOBX|..." → "OBX|..." : a 4-char name ending in OBX, where char 5 is a delimiter.
        if (line.length > 4 && !line[0].isWhitespace() &&
            line[1] == 'O' && line[2] == 'B' && line[3] == 'X'
        ) {
            return "OBX" + line.drop(4)
        }
        return line
    }
}
