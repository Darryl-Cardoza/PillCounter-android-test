package org.rite.hl7.encoding

/**
 * Escapes and unescapes HL7 v2.x text using the message's [HL7Delimiters].
 *
 * Canonical escape table (single source of truth — fixes the swapped ~/&
 * mapping that existed in the old builder):
 *
 * | character        | escape |
 * |------------------|--------|
 * | escape   (`\`)   | `\E\`  |
 * | field    (`\|`)  | `\F\`  |
 * | component(`^`)   | `\S\`  |
 * | subcomp  (`&`)   | `\T\`  |
 * | repetition(`~`)  | `\R\`  |
 *
 * On decode we additionally handle `\Xhh..\` (hex bytes → UTF-8) and pass any
 * unrecognized `\...\` sequence through verbatim so non-delimiter escapes
 * (e.g. formatting `\.br\` or custom `\Zxx\`) survive a round trip.
 */
object HL7Escaping {

    /**
     * Escapes a raw application string for the wire. The escape character is
     * replaced first so that escapes introduced for the other delimiters are
     * not double-escaped.
     */
    fun escape(raw: String, d: HL7Delimiters): String {
        if (raw.isEmpty()) return raw
        // Fast path: nothing to escape.
        if (raw.none { it == d.escape || it == d.field || it == d.component || it == d.subcomponent || it == d.repetition }) {
            return raw
        }
        val e = d.escape
        val sb = StringBuilder(raw.length + 8)
        for (ch in raw) {
            when (ch) {
                d.escape -> sb.append(e).append('E').append(e)
                d.field -> sb.append(e).append('F').append(e)
                d.component -> sb.append(e).append('S').append(e)
                d.subcomponent -> sb.append(e).append('T').append(e)
                d.repetition -> sb.append(e).append('R').append(e)
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * Decodes wire text to a raw string. Resolves the five delimiter escapes,
     * `\Xhh..\` hex sequences, and leaves unknown escapes intact.
     */
    fun unescape(wire: String, d: HL7Delimiters): String {
        val e = d.escape
        if (wire.indexOf(e) < 0) return wire

        val sb = StringBuilder(wire.length)
        var i = 0
        while (i < wire.length) {
            val ch = wire[i]
            if (ch != e) {
                sb.append(ch)
                i++
                continue
            }
            // Find the closing escape character.
            val close = wire.indexOf(e, i + 1)
            if (close < 0) {
                // Unterminated escape — emit the rest verbatim.
                sb.append(wire.substring(i))
                break
            }
            val code = wire.substring(i + 1, close)
            val decoded = decodeEscape(code, d)
            if (decoded != null) {
                sb.append(decoded)
            } else {
                // Unknown escape — keep the full sequence verbatim.
                sb.append(e).append(code).append(e)
            }
            i = close + 1
        }
        return sb.toString()
    }

    /** Returns the decoded text for an escape code, or null if unrecognized. */
    private fun decodeEscape(code: String, d: HL7Delimiters): String? {
        if (code.isEmpty()) return null
        return when (code[0]) {
            'E' -> if (code.length == 1) d.escape.toString() else null
            'F' -> if (code.length == 1) d.field.toString() else null
            'S' -> if (code.length == 1) d.component.toString() else null
            'T' -> if (code.length == 1) d.subcomponent.toString() else null
            'R' -> if (code.length == 1) d.repetition.toString() else null
            'X' -> decodeHex(code.substring(1))
            else -> null
        }
    }

    /** Decodes `Xhh..` hex digit pairs into a UTF-8 string, or null if malformed. */
    private fun decodeHex(hex: String): String? {
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        val bytes = ByteArray(hex.length / 2)
        var i = 0
        while (i < hex.length) {
            val hi = hexDigit(hex[i]) ?: return null
            val lo = hexDigit(hex[i + 1]) ?: return null
            bytes[i / 2] = ((hi shl 4) or lo).toByte()
            i += 2
        }
        return bytes.decodeToString()
    }

    private fun hexDigit(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> null
    }
}
